# Migration plan — unify on the aggregate poker engine

Goal: make `domain.aggregate.PokerGame` (the pure DDD kernel) the **single** engine for every poker flow —
cash, play-money games, and all tournament types — then retire the legacy engine. Zero user-visible behaviour
change; the existing 1090+ test suite is the regression net throughout.

This is an **internal-quality investment** (one engine instead of two, no rules drift, decoupled from Hibernate,
unit-testable rules, domain-event extension points). It is *not* a feature. Do it only if the engine will keep
evolving (new variants/game types) or the two-engine drift becomes a maintenance cost.

---

## 0. Current state (measured)

- Two engines, selected by `app.game.engine` (`GameEngine.LEGACY` default; `application.properties:
  app.game.engine=legacy`).
  - **legacy** — `service/PokerGameService.java` (~1520 LOC) mutating the JPA `model.Game` / `model.Player`
    entities in place; wrapped by the production orchestration (Redis hot-state, async-persist, hand-lifecycle
    timer, turn-timeout, cluster routing/ownership, stats, hand history, bots). Used by **all tournaments +
    play-money games**.
  - **aggregate** — `domain/aggregate/PokerGame.java` (~1160 LOC, JPA/Spring-free) + `domain/value/*`
    (`Chips`, `HandRanker`, `HandStrength`, `Position`, `BettingRound`) + domain events (`domain/event/*`).
    Persistence via `mapper/PokerGameMapper` ↔ `PersistedGameState` ↔ the same `games` table. Has
    golden/showdown/betting unit tests. Used **only by cash** (via `service/CashGameService`, which drives the
    kernel directly and persists with its own correct flow).
- **The aggregate path inside `PokerGameService` is currently broken** (proven this session):
  - `FullGameFlowIT` with `app.game.engine=aggregate` → 22/23 errors:
    `DataIntegrityViolation: Detached entity ... has an uninitialized version value 'null': Game.version`.
  - `FederatedPyramidFinalIT` with aggregate → `IllegalStateException: Parallel pyramid table processing failed`
    at `PokerGameService.createNewGameViaAggregate`.
- Cash works on the aggregate kernel because `CashGameService` does **not** go through the broken
  `PokerGameService` aggregate path — it builds `new Game()` (no manual id), maps + `save()` (clean insert).

---

## 1. Root causes to fix (the blockers)

- **D1 — new-game persist treats the entity as detached.**
  `PokerGameService.createNewGameViaAggregate` does `Game game = new Game(); game.setId(aggregate.getId()); …
  gameStateService.persistFullSync(game)`. Assigning the id to a fresh entity with a null `@Version` makes
  `gameRepository.save()` behave as a **merge of a detached entity** → "uninitialized version" error.
  Fix: insert as a new entity — either `entityManager.persist(game)`, or **drop `setId(...)`** and let the id
  generate (mirroring `CashGameService.openHand`, which works), or initialise the version. This single fix
  almost certainly clears both `FullGameFlowIT` and the parallel-pyramid failure (same root in
  `processRoundTables`).
- **D2 — parallel pyramid round processing on the aggregate path.** Once D1 is fixed, re-verify
  `processRoundTables` (worker threads each create/play a game) under aggregate, applying the slice-6c lessons
  (`EntityManager.clear()` after a round; synchronous persist while driving; no async-persist/lifecycle race —
  see the pyramid fix already committed).
- **D3 — behavioural parity gaps.** Confirm the aggregate kernel reproduces legacy outcomes for the hard cases:
  side pots, multi-way all-in, split pots / ties, the **dead-button** rule on mid-hand elimination (tournament
  specific), missed blinds, and MTT table rebalancing (tournaments). Golden tests exist but must be diffed
  against legacy behaviour (Phase B).

---

## 2. Strategy

Incremental, flag-gated, reversible. Keep `legacy` as the default until parity is **proven**, migrate flow types
one at a time behind `app.game.engine`, flip the default only after the full suite + live smoke pass on
aggregate, then delete legacy in a final cleanup slice. A **characterization (golden) test net** is built before
touching shared behaviour so any divergence surfaces immediately.

Each phase below is one (or a few) slice(s) in the project cadence: green build, full verification, fresh-Postgres
where schema-relevant, docs + commit + push.

---

## 3. Phases

### Phase A — Fix the aggregate creation/persist path (D1)  ·  ✅ DONE (commit)
- **Done:** dropped the manual `game.setId(aggregate.getId())` in `createNewGameViaAggregate` so a fresh `Game`
  inserts (id generated on persist) instead of being treated as a detached merge. No other aggregate entry point
  had the same `setId(...)` hazard. The legacy default is untouched (the change only affects the aggregate path),
  full surefire suite (1096) stays green on `engine=legacy`.
- **Result on `engine=aggregate`:** the `DataIntegrityViolation: uninitialized version` errors are **gone** —
  games now create + play on the aggregate engine. `FullGameFlowIT` went from 22 persistence *errors* to **0
  errors, 6 failures** (WebSocket subgroup fully green). The 6 remaining are **behavioural parity gaps**, not
  crashes: bot integration (3), hand-history recording (1), error-recovery / invalid-raise handling (2). These
  belong to Phases B/C below — the `FullGameFlowIT`-green gate therefore moves to **after Phase C**.

> **Reconnaissance finding (the keystone parity issue) — `Game.finished` is overloaded.**
> The legacy engine and every test/driver treat `Game.finished` as **"the current hand is done"** (set true at
> showdown/fold, reset to false when the next hand is dealt). The aggregate's `PokerGame.isFinished()` means
> **"the whole match is over"** (≤1 player left with chips). The `mapper/PokerGameMapper` bridges both
> *directions* through this single field — `applyToGame` writes `game.finished = aggregate.isFinished()` and
> `fromGame`/`reconstitute` reads `game.finished` back into `aggregate.finished`. A naive one-line flip in
> `applyToGame` (set finished from `phase == FINISHED`) was probed: it fixed hand completion + hand-history +
> the two bot tests on the aggregate engine, but **broke multi-hand flow** (`fromGame` then read the hand-done
> flag as match-over, so `aggregate.startNewHand()` threw "game has already finished") — net 6→6, so it was
> reverted to keep the tree clean. **Proper Theme-1 fix (a real Phase-C sub-slice, ~2 files + tests):** make
> `Game.finished` mean **hand-done** everywhere — `applyToGame` sets it from `phase == FINISHED`, and
> `reconstitute`/`PersistedGameState` must **derive** the aggregate's match-over from the chip counts (≤1 player
> with chips) instead of reading it back from `Game.finished`. Cash is unaffected either way (it reads
> `aggregate.getPhase() == FINISHED`, never `Game.finished`).

### Phase B — Characterization / golden parity net  ·  STARTED
- **Existing nets (reuse):** `PokerGameRulesGoldenTest` (aggregate) ↔ `GameRulesGoldenTest` (legacy) pin the
  bookkeeping (dead button / missed blinds / last aggressor); `PokerGameShowdownTest` pins showdown invariants
  (chip conservation, side-pot zero-sum, winner metadata) deck-independently. Hand ranking is covered by the
  `HandRanker` golden tests.
- **Done (seed):** `CrossEnginePokerParityIT` — the same deck-independent hands are run on **both** engines
  (flipping `app.game.engine` at runtime, restored in `finally`) and the **final stacks + winner must be
  identical**. Four scenarios green (3-handed fold-out, heads-up fold, raise→fold, raise→re-raise→fold), so
  legacy and aggregate agree byte-for-byte on the position / blind / bet / raise / fold bookkeeping. This is the
  cross-engine oracle the deep Phase-C changes run against; it grows (multi-hand, showdown with a deterministic
  deck, bots) as each Phase-C gap is closed.
- **Done (deterministic deck):** added a package-private `PokerGame.useFixedDeck(List<Card>)` test seam (null
  in production → no behaviour change; `shuffleDeck()` deals from it in order when set). `PokerGameDeterministic
  ShowdownTest` uses it to pin an exact main + two side-pot distribution (3 unequal all-in stacks; AA/KK/QQ on a
  blank board → P2 wins the main pot, P1 side pot 1, P0 the uncontested side pot 2 back). This locks the
  aggregate's showdown/side-pot math (beyond the zero-sum invariant already in `PokerGameShowdownTest`).
- **Next in B:** a matching deterministic-deck seam on the *legacy* deal so the **same** pinned board can be
  compared cross-engine at showdown (currently the cross-engine net is deck-independent fold/bet only; the
  deterministic showdown is pinned on the aggregate alone).
- With a **seeded/deterministic deck**, capture legacy outcomes (winners, final chips, pot, board, side pots)
  for a representative battery: HU check/call showdown, 3-way pot, multi-way all-in with side pots, split pot,
  pre-flop fold-walk, dead-button elimination, missed blinds.
- Run the identical scenarios against the aggregate engine; assert **identical** results. These tests become the
  oracle for every later phase (run them with the flag both ways).
- Where the deck isn't seedable today, add a test-only deck seam (no production change).

### Phase C — Orchestration parity on the aggregate path
- Verify each production concern works when `engine=aggregate`: hand-lifecycle (`GameHandLifecycleService`),
  turn-timeout, Redis hot-state (`GameStateCoordinator`/`RedisGameStateStore`), async-persist, cluster
  routing/ownership (`TableCommandDispatcher`, `TableOwnershipService`, `ClusterActionForwarder`), statistics
  (already via domain events on the aggregate path), hand history, and **bot actions**
  (`executeBotActionInternal` → `playerActViaAggregate`). Fix gaps; add ITs per concern.

### Phase D — Pyramid / parallel processing on aggregate (D2)
- Make `PyramidTournamentService.processRoundTables` correct under aggregate (worker-thread persistence,
  version safety, OSIV-clear as in the committed pyramid fix).
- **Gate:** `PyramidTournamentIT`, `PyramidAdvanceRoundIT`, `FederatedPyramidFinalIT`,
  `FederatedPyramidServiceIT` green with `engine=aggregate`.

### Phase E — Migrate flows one type at a time (still flag-gated)
- Order by risk: **SNG / Freezeout → Rebuy / Bounty → Pyramid → Federated pyramid → buy-up variants**.
- For each: run that type's full IT set with `engine=aggregate`; green = that type is parity-ready.
- Keep the default `legacy`; this phase only proves readiness per type.

### Phase F — Flip the default to aggregate
- Set `app.game.engine=aggregate` as the default; run the **entire** surefire suite (1090+) plus every `*IT`
  individually, the golden net both ways, a fresh-Postgres `validate`, a **live smoke** on the dev stack
  (create + play a SNG, a pyramid, a cash table), and re-run the WS **load test** to catch any perf regression.
- Provide a one-line rollback (`app.game.engine=legacy`) until F is fully signed off in an environment.

### Phase G — Retire legacy
- Delete the legacy engine code in `PokerGameService` (the imperative betting/showdown/pot logic and its
  helpers) and any now-dead orchestration branches; collapse the `app.game.engine` flag and `GameEngine` enum;
  remove `usesAggregateEngine()` branching so there is one path.
- Tighten ArchUnit (the domain stays pure; `PokerGameService` becomes a thin orchestrator over the aggregate).
- Final full verification + docs (README engine section, CHANGELOG, FUTURE_IMPROVEMENTS).

---

## 4. Verification (every phase)

1. `cd backend && ./mvnw -o test` — full surefire green (legacy default until Phase F).
2. The relevant `*IT` run individually with **both** `engine=legacy` and `engine=aggregate` until F.
3. Golden parity net (Phase B onward) green both ways.
4. Fresh-Postgres `ddl-auto=validate` when persistence/mapping changes.
5. Phase F also: live smoke on the dev stack + WS load test for perf.
6. **No concurrent maven runs** (a lesson from this session — overlapping `mvnw` forks corrupt `target/` and
   surfaced as spurious `ClassNotFoundException` cascades; run suites one at a time).

---

## 5. Rollback & decision gates

- **Reversible at all times** until Phase G: the default stays `legacy`; flipping the flag back is the rollback.
- **Gate 1 (after A+B):** is parity cheap to reach? If the golden net shows many subtle diffs, re-scope or stop —
  the cash/tournament split is a perfectly acceptable steady state.
- **Gate 2 (after E):** all flow types green on aggregate → schedule the default flip (F).
- **Gate 3 (after F, in a real environment):** soak before G (legacy deletion is irreversible).

---

## 6. Risk register

| Risk | Mitigation |
|---|---|
| Hidden behaviour diffs (side pots, dead button, ties, MTT rebalance) | Golden parity net (Phase B), run both ways every phase |
| Persistence races (OSIV staleness, async-persist, hot-state) | Reuse the committed pyramid fix pattern (EntityManager.clear after a driven round, synchronous persist, suppressed lifecycle); add ITs |
| `Game.version` / detached-entity insert hazard | Phase A fix + an explicit "new game inserts" test |
| Performance regression | Re-run the WS load test in Phase F |
| Tournament regression during the long migration | Legacy stays default until F; per-type gating in E |

---

## 7. Effort

Roughly **8–12 slices**. Phase A is small and unblocks the rest; Phases B and D are the heavy lifts (the golden
net and parallel-pyramid persistence); Phase G is mechanical cleanup. None of it ships a user-facing feature, so
sequence it between feature work, not instead of it.

---

## 8. First concrete step

Phase A: fix `PokerGameService.createNewGameViaAggregate` to insert a new `Game` (drop the manual
`setId(aggregate.getId())` or use `persist`), then prove `FullGameFlowIT` green with `engine=aggregate`. That one
change is expected to clear both currently-failing aggregate probes and is the smallest, safest place to start.
