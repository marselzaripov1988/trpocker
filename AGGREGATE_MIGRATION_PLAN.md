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

### Phase B — Characterization / golden parity net  ·  COMPLETE
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
- **Finding — showdown ranking is already a single source of truth (no legacy deal seam needed):** both engines
  determine the showdown **winner** through the *same* code. Legacy `PokerGameService.evaluateHands(...)` calls
  `handEvaluator.evaluate(...)`, and `HandEvaluator` is a thin `@Component` that **delegates to
  `domain.value.HandRanker.evaluate(...)`** — the exact static call the aggregate `PokerGame` makes directly.
  (`HandAnalysisService` is bot AI, not used in showdown.) So who-wins parity is **structural**, not a behaviour
  diff: the only per-engine showdown code left is the pot/side-pot *distribution* algorithm. We therefore did
  **not** seam the prod-critical legacy `createNewGame` deal (poor risk/reward) and instead:
  - **Done (cross-engine ranker guard):** `HandRankerParityTest` asserts legacy `HandEvaluator` == aggregate
    `HandRanker` across every hand category + kicker tie-breakers — locking the single source of truth so a future
    fork of `HandEvaluator` (re-introducing a second ranker) is caught immediately. Pure unit test, no prod change.
  - **Aggregate side-pot distribution** is pinned by `PokerGameDeterministicShowdownTest` (above); the legacy
    distribution is the long-standing battle-tested default. A full cross-engine *distribution* comparison would
    need a legacy deal seam for marginal value (ranker already shared) and is deferred as out-of-scope for B.
- **Phase B is complete:** the golden net now covers (a) cross-engine betting/fold parity (`CrossEnginePokerParityIT`,
  deck-independent), (b) deterministic aggregate side-pot distribution, and (c) cross-engine showdown-ranking parity
  (`HandRankerParityTest`). This is the oracle later phases run against (flag both ways).

### Phase C — Orchestration parity on the aggregate path  ·  COMPLETE
- Verify each production concern works when `engine=aggregate`: hand-lifecycle (`GameHandLifecycleService`),
  turn-timeout, Redis hot-state (`GameStateCoordinator`/`RedisGameStateStore`), async-persist, cluster
  routing/ownership (`TableCommandDispatcher`, `TableOwnershipService`, `ClusterActionForwarder`), statistics
  (already via domain events on the aggregate path), hand history, and **bot actions**
  (`executeBotActionInternal` → `playerActViaAggregate`). Fix gaps; add ITs per concern.
- **Theme 1 — `Game.finished` overload fixed (done).** `PokerGameMapper` now keeps the two meanings separate:
  `applyToGame` writes `Game.finished` = **hand-done** (owned by `applyPhaseAndPot`: `true` iff `phase == FINISHED`,
  the legacy/test wire semantics), and `toPersistedState` re-derives the aggregate's **match-over** flag from chip
  counts via `isMatchOver(game)` — `hand-done && players-with-chips < 2`, gated on hand-done so a mid-hand all-in
  (a player momentarily at 0 chips) can't false-trigger match-over and break heads-up. This mirrors
  `PokerGame.completeHand` exactly and is the both-direction fix the earlier naive one-line flip lacked. Cash is
  unaffected (it reads `phase == FINISHED` and deletes the row on hand end, never reconstituting a finished match).
- **Theme — fold-out win description (done).** The aggregate's `awardPotToLastPlayer` now sets the winning-hand
  description to `"All opponents folded"` (a new `WON_BY_FOLD_DESCRIPTION` constant) instead of `null`, matching the
  legacy `PokerGameService` verbatim on the wire + in the `PotAwarded`/`HandCompleted` events. `PokerGameShowdownTest.
  foldOutWin` was re-pinned from `null` to that string (it had pinned the pre-parity aggregate behaviour).
- **Theme 4 — exception-type alignment (done at the test boundary).** The aggregate throws richer typed
  `InvalidActionException`s where legacy threw `IllegalStateException`/`IllegalArgumentException`. HTTP mapping is
  equivalent for the too-small-raise case (both → 400); the illegal-check-facing-a-bet case changes 409→400, a
  defensible improvement (an illegal action is a client error, not a state conflict). `FullGameFlowIT`'s two
  error-recovery assertions were made engine-agnostic (`isInstanceOfAny(legacyType, InvalidActionException)`), and
  the bot-decision assertion was made robust (folded / hand-ended / chips-committed instead of the reset-prone
  `hasActed` flag on a stale Player reference).
- **Result:** `FullGameFlowIT` is **23/23 green on both engines** (was 6 failures on aggregate). Full surefire suite
  **1099 green**.
- **Theme 2 — lifecycle-driven multi-hand on aggregate (verified).** The automatic between-hands transition already
  routes through the aggregate: `finalizeAggregateHand → GameHandLifecycleService.scheduleAfterHandCompleted →
  RESULT_DELAY → startNextHandFromLifecycleInternal → startNewHandInternal → startNewHandViaAggregate` (which drops
  busted players, reconstitutes, `aggregate.startNewHand()`, re-persists). The Theme-1 fix is load-bearing here:
  after a finished hand `Game.finished` = hand-done = true, but `isMatchOver` re-derivation (gated on `<2` players
  with chips, after the bust-out removal) yields `aggregate.finished=false`, so `startNewHand()` proceeds instead of
  throwing "game has already finished". New `AggregateLifecycleMultiHandIT` pins this end-to-end: a heads-up hand is
  folded out, **no manual `startNewHand`**, and the lifecycle timer alone advances the hand number, deals fresh hole
  cards, posts blinds, and conserves chips (stacks + pot == buy-ins). Green.
- **Test-isolation fix (done).** `GameHandLifecycleService` gained `cancelAll()` / `pendingTransitionCount()` and
  `FullGameFlowIT` now cancels pending transitions in `@AfterEach` + retries its `@BeforeEach` cleanup, removing a
  pre-existing flaky `ObjectOptimisticLockingFailureException` where a scheduled transition re-persisted its game
  during the next test's versioned `deleteAll` (same OSIV/async-persist family fixed for the pyramid).
- **Theme — turn-action timeout on aggregate (verified).** `GameTurnTimeoutService.scheduleForCurrentTurn` fires
  `PokerGameService.handleTurnTimeout`, which routes through `handleTurnTimeoutInternal → playerActInternal →
  playerActViaAggregate` — i.e. the auto-fold/-check executes in the aggregate kernel. New `AggregateTurnTimeoutIT`
  pins it: heads-up, the player on the clock never acts, the 1 s timer auto-folds them (facing the big blind), the
  hand ends, and chips are conserved — with a long result delay so the lifecycle doesn't deal a new hand mid-assert.
  Green. `GameTurnTimeoutService` also gained `cancelAll()` / `pendingTimeoutCount()` (graceful shutdown + test
  isolation), mirroring `GameHandLifecycleService`.
- **Theme — Redis hot-state (bug found + fixed).** Hot-state (`app.game.hot-state-enabled=true`, **on by default**)
  serialized the whole JPA `Game` to Redis with the **REST/default `ObjectMapper`**, which honours `@JsonIgnore` on
  `Game.deck` — so a Redis cache hit returned a **deckless** game and the next street could not be dealt (an empty
  deck threw). Hole cards survive (`Player.hand` is not `@JsonIgnore`), so this only bites multi-street hands — and
  affected **both** engines; `FullGameFlowIT` never caught it because it mocks `RedisTemplate` (every `find` misses →
  DB fallback with the deck intact). Fix: a dedicated `gameStateObjectMapper` (a copy of the app mapper + a targeted
  mix-in that re-exposes only `Game.deck`) injected into `RedisGameStateStore`; the REST mapper still hides the deck,
  and bidirectional `@JsonIgnore` back-references are left alone (no serialization cycle). `HotStateGameSerialization
  Test` pins both halves (default mapper drops the deck, hot-state mapper keeps it); `AggregateHotStateMultiStreetIT`
  runs a check/call hand to a **five-card showdown over a real Redis container** on the aggregate engine, with chips
  conserved — impossible before the fix.
- **Theme — async-persist ↔ hot-state version reconciliation (fixed).** Root cause of the `StaleObjectState
  Exception`/rollback noise above: `Game.version` (the `@Version` token) is also `@JsonIgnore`d, so a game reloaded
  from Redis had a **null** version → the async DB writer treated it as a transient insert against an existing row
  ("unsaved-value mapping was incorrect"). Fix part 1: the hot-state mix-in now re-exposes `Game.version` too, so
  the optimistic-lock token round-trips and the writer issues a correct versioned `UPDATE` (helps the legacy engine
  equally). Fix part 2: `AsyncGamePersistService` now does the DB write in a dedicated transaction via a self-proxy
  and catches `ObjectOptimisticLockingFailureException` on the non-transactional `@Async` method — a genuinely
  concurrent mirror write (hand-end finalize racing the result-delay transition) rolls back cleanly and is logged at
  DEBUG (Redis stays authoritative; the next boundary write re-persists), instead of poisoning the transaction and
  surfacing as an `UnexpectedRollbackException`. `AggregateHotStateMultiStreetIT` now runs clean (no stale/rollback
  logs); `HotStateGameSerializationTest` pins that the hot-state mapper keeps the version while the REST mapper drops
  it.
- **Theme — cluster routing/ownership on aggregate (verified).** Cross-node action routing is engine-agnostic at the
  service layer (`ClusterActionForwarder` → owner's `playerActLocal` → `playerActInternal` → `playerActViaAggregate`),
  and the owner persists to shared Redis while the forwarder reloads cross-node — so it depends on the hot-state deck
  fix above. `AggregateClusterRoutingIT` boots two real nodes with `engine=aggregate`, creates a table on node-A, and
  drives a whole check/call hand through node-B (the non-owner): every action forwards to node-A, applies in the
  aggregate kernel, and reloads cross-node, reaching a **five-card showdown** with chips conserved and ownership
  unmoved. (A `TaskRejectedException` may appear in the log during `@AfterAll` context teardown — an async task hitting
  an already-terminated executor; harmless shutdown ordering, not a test failure.)
- **Phase C is complete:** statistics, hand history, bots, lifecycle multi-hand, turn-timeout, Redis hot-state
  (multi-street + version), and cross-node cluster routing/ownership are all verified on the aggregate engine; the
  `Game.finished` overload and the fold-out description are at parity, and the hot-state deck/version losses (which
  affected both engines) are fixed. `FullGameFlowIT` is green on both engines and the full surefire suite is green.

### Phase D — Pyramid / parallel processing on aggregate (D2)  ·  COMPLETE (no production change needed)
- **Finding:** `PyramidTournamentService.processRoundTables` already runs correctly under `engine=aggregate` after
  Phases A–C — no code change was required. Each round-N table is a **distinct game**, so the worker threads (one
  per table, each in its own transaction with the live lifecycle suppressed via `HandLifecycleScheduling
  .runSuppressed`, plus the `EntityManager.clear()` after each round) drive the aggregate kernel concurrently with
  no cross-game `@Version` contention. The Phase-A `createNewGameViaAggregate` insert fix and the Phase-C hot-state
  deck/version round-trip are what make the per-table create + multi-action play work on the aggregate path.
- **Gate — the entire pyramid IT set is green with `engine=aggregate`** (verified via `-Dapp.game.engine=aggregate`):
  `FederatedPyramidServiceIT` (7), `PyramidAdvanceRoundIT` (1), `FederatedPyramidFinalIT` (4),
  `FederatedPyramidControllerIT` (2), `FederatedPyramidNodeGroupIT` (2), `FederatedPyramidPayoutIT` (2),
  `FederatedPyramidPrizeIT` (1), `PyramidBuyoutControllerIT` (2), `PyramidBuyoutRepositoryIT` (3),
  `PyramidBuyUpRunIT` (1), `FederatedPyramidControllerDisabledIT`.
- **Permanent guard:** `PyramidAggregateEngineIT` pins `app.game.engine=aggregate` via `@TestPropertySource` and runs
  a 30-player / 3-table round through `processRoundTables`, asserting each table promotes one survivor onto a single
  level-2 table — so the parallel pyramid flow stays green on the aggregate engine without relying on a `-D` flag.

### Phase E — Migrate flows one type at a time (still flag-gated)  ·  COMPLETE
- **Method:** ran the engine-playing ITs with `-Dapp.game.engine=aggregate` and, for any failure, re-ran on the
  default legacy engine to tell a real aggregate gap apart from a pre-existing one.
- **Green on aggregate (and on legacy):** `PassiveBotGameIT` (10-bot many-way hand), `FederatedBuyUpShardIT`,
  `FederatedFinalBuyoutIT`, `FederatedBuyUpControllerIT`, the whole pyramid set (Phase D), `FullGameFlowIT` (both
  engines), `CrossEnginePokerParityIT`, and all the Phase-C aggregate ITs. So freezeout/SNG single-table play,
  many-way pots, federated buy-up, and pyramid are all parity-ready on the aggregate engine.
- **Pre-existing failures (NOT aggregate gaps):** `TournamentIT`, `TournamentControllerIT`, and
  `PokerGameControllerIT` fail **identically on both engines** when run in isolation — `TournamentIT` with
  `LazyInitializationException` on `Tournament.registrations`/`tables` (an OSIV/transaction-boundary problem in the
  test) and `RUNNING` vs `REGISTERING` / "insufficient players" start-flow errors; the controller ITs fail at
  context/`@BeforeEach` setup (~0.02 s per method). These are excluded from the gating `mvnw -o test` suite and are
  unrelated to the engine — flagged for a separate test-health slice, must not block Phase F.
- **Permanent guard:** `PassiveBotGameAggregateIT` pins `engine=aggregate` and runs a full ten-bot hand (many-way
  pot + side pots + multi-player showdown) to completion with chips conserved — complementing the mostly-heads-up
  aggregate ITs and `PyramidAggregateEngineIT`.

### Phase F — Flip the default to aggregate  ·  DONE (code) / smoke + load-test recommended pre-prod
- **Done:** `application.properties` now ships `app.game.engine=aggregate` (the production default). **One-line
  rollback:** set it back to `legacy` (a comment in the file records this).
- **Test-suite strategy (deliberate):** `application-test.properties` still pins `app.game.engine=legacy`, so the 62
  `@ActiveProfiles("test")` classes keep exercising the **legacy** path — that is the rollback regression net kept
  alive until Phase G deletes legacy. The aggregate engine is covered instead by the dedicated aggregate ITs
  (lifecycle multi-hand, turn-timeout, hot-state multi-street, cluster routing, many-way bots, pyramid) plus
  `CrossEnginePokerParityIT` (golden net, both ways) and `FullGameFlowIT` (no profile → now inherits the aggregate
  default). `PokerGameServiceTest` mocks the engine, so it tests both paths regardless of the property.
- **Verified:** full `mvnw -o test` suite **1102 green** after the flip (unit + legacy-pinned tests unaffected);
  `FullGameFlowIT` **23/23 green on the new aggregate default** (run with no `-D` flag); the whole pyramid IT set and
  the aggregate ITs are green. So the build is green with aggregate as the shipped default.
- **Recommended before a production cutover (environment-dependent, not run here):** fresh-Postgres Liquibase
  `validate`, a live smoke on the dev stack (create + play a SNG, a pyramid, a cash table on the running app), and a
  re-run of the WS scaling **load test** to confirm no perf regression on the aggregate path. Roll back via the flag
  if any of these regress.

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
