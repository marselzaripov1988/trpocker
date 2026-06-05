# Changelog

All notable changes to TruHoldem will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### 🔺 Federated pyramid — slice 7: real money (buy-in + prize distribution)
- A federation can carry a crypto buy-in (`crypto_buy_in_amount` / `crypto_buy_in_asset`, changeset 18;
  null/zero = play-money). `register` charges the buy-in via `WalletService.chargeBuyIn` (idempotent key
  `fedbuyin:…`; an under-funded wallet throws and rolls the registration back). On completion,
  `distributePrizes` pays the pool (`registered × buyIn`): `app.tournament.federated-shard-prize-bps`
  (default 30%) is split equally among the shard winners as a qualifier prize and the remainder goes to the
  grand champion (who, as a shard winner, also collects a qualifier); rounding is absorbed into the champion's
  share so payouts sum exactly to the pool. Idempotent per-recipient award keys (`fedqual:…`, `fedchamp:…`).
  `createFederation` gained a buy-in overload; `CreateFederationRequest` gained optional `buyInAmount` /
  `buyInAsset`.
- Verified by `FederatedPyramidPrizeIT` (8 funded players, buyIn 20, bps 3000 → pool 160): each registration
  charges the buy-in (balance 80), and after the full run the champion holds exactly 204 (100 − 20 + 12 + 112),
  other shard winners 92, non-winners 80, and the total across players is conserved at 800 (the pool is fully
  redistributed). Changeset 18 runs clean on a fresh Postgres (`ddl-auto=validate`); surefire suite green (1090).

### 🔺 Federated pyramid — slice 6c: player UI (+ admin route fix)
- New player view `FederationViewComponent` at `/federations/:id` (auth-guarded): shows the field's status,
  shard progress (registered / running / done-of-total), the scheduled final and grand champion, and a
  **Register** button (enabled while REGISTERING/SHARDS_RUNNING) that assigns the caller to a shard and then
  shows "you're in shard #N (status)". New player `FederationService` + `federation.models.ts`.
- **Fix (from 6b):** the admin federations route had been added under `ADMIN_ROUTES` (mounted at
  `/admin/tournaments`), so it resolved at `/admin/tournaments/federations` while the nav link pointed at
  `/admin/federations` — broken. Moved it to a proper top-level `admin/federations` route in `app.routes.ts`
  (mirroring `admin/pool`), so the **🔗 Pyramids** nav link now works.
- Verified: eslint clean and `ng build --configuration production` green.

### 🔺 Federated pyramid — slice 6b: admin UI
- New admin page **🔗 Pyramids** (`/admin/federations`, behind the existing admin guard + nav link): a create
  form (name, starting players, shard size, optional registration deadline = blank for indefinite) and, once
  created, a live detail/lifecycle panel — status badge, shard/seat/registered stats, per-shard-status chips
  (PENDING/REGISTERING/READY/RUNNING/COMPLETED), scheduled-final time, grand champion — with lifecycle
  buttons: **Promote waves**, **Run shards**, **Schedule final + e-mail finalists** (datetime picker → ISO),
  **Start final**, **Run final**, **Refresh**. New `AdminFederationService` (wraps the slice-6a endpoints) +
  `admin-federation.models.ts` mirroring the backend DTOs; results/errors surface via the global toaster.
- Verified: `eslint` clean and `ng build --configuration production` green. The player-facing register/standing
  view is a small follow-up (slice 6c).

### 🔺 Federated pyramid — slice 6a: REST (admin + player)
- `AdminPyramidFederationController` (`/v1/admin/pyramid-federations`, ADMIN): create a federation, read its
  detail (status + per-shard-status counts + champion), promote waves, schedule-final, start-final, run-final,
  drain-shards. `PyramidFederationController` (`/v1/pyramid-federations`): the authenticated player registers
  (assigned to a shard, returns the shard), and reads federation status. Both gated by
  `app.tournament.federated-pyramid-enabled` (404 when off). New DTOs `CreateFederationRequest`,
  `FederationDetailResponse`, `FederationRegistrationResponse`; reuses `ScheduleTournamentRequest` for the
  final time. `FederatedPyramidService.getFederationDetail` builds the read view (service→DTO, no schema
  change).
- Verified by `FederatedPyramidControllerIT` (HTTP, flag on): admin create → 4 shards (1 REGISTERING / 3
  PENDING), player register → shard 0, detail shows `registeredPlayers=1`; scheduling the final before the
  shards are done → 409. ArchUnit + surefire suite green (1090). Frontend admin/player UI is the next step
  (slice 6b).

### 🔺 Federated pyramid — slice 5: final run → grand champion (engine lifecycle complete)
- `FederatedPyramidService.runFinalToChampion` runs the FINAL_RUNNING final pyramid to its single winner via
  `PyramidTournamentService.runToCompletion` (not wrapped in a transaction — same pattern as a shard run),
  then `recordChampion` (self-proxy transaction, idempotent) sets `champion_player_id` and marks the
  federation **COMPLETED**. This closes the full engine lifecycle: register → wave-fill shards → run each
  shard to a winner → barrier → admin-scheduled final → final run → one grand champion. No schema change.
- Verified by a new `FederatedPyramidFinalIT` end-to-end scenario (8-player / 4-shard federation, seats=2):
  fill → drain → schedule → start → `runFinalToChampion` yields a non-null champion, the federation is
  COMPLETED with that `champion_player_id`, and the champion is one of the four shard winners. Surefire suite
  green (1090).

### 🔺 Federated pyramid — slice 4: finalists barrier + admin-scheduled final
- With the federation at AWAITING_FINAL (all shard winners gathered), `FederatedPyramidService.scheduleFinal`
  lets an admin set the final's start time (any future instant) → FINAL_SCHEDULED, and e-mails every finalist
  via a new `TournamentNotificationService.notifyFederationFinalScheduled` + `EmailService`
  "you reached the final" template (resolving each shard winner's owning user; bots/no-user are skipped).
  `startFinal` then creates the final PYRAMID tournament, seeds it from the shard winners (preserving their
  names), starts it, and moves the federation to FINAL_RUNNING (running it to the grand champion is slice 5).
  No schema change (uses the existing `final_scheduled_start` / `final_tournament_id` columns).
- Verified by `FederatedPyramidFinalIT`: scheduling e-mails all three real-user finalists and sets
  FINAL_SCHEDULED + the time; an end-to-end 8-player/4-shard federation drains → schedules → `startFinal`
  seeds the 4 winners into the final pyramid (FINAL_RUNNING); and the guards reject scheduling before all
  shards are done or for a past time. Surefire suite green (1090).

### 🔺 Federated pyramid — slice 3: shard run → winner capture
- `FederatedPyramidService.runShardToWinner` runs a RUNNING shard's pyramid to its single winner via
  `PyramidTournamentService.runToCompletion` (which manages its own per-round transactions, so the run is
  intentionally not wrapped in one big transaction), then records the winner in a separate transactional step
  (via a `@Lazy` self-proxy): `recordShardWinner` marks the shard COMPLETED with its `winner_player_id`,
  promotes the next READY shard into the freed wave slot, and — once every shard is done — flips the
  federation to **AWAITING_FINAL** (the barrier for the admin-scheduled final). `drainShards` drives the whole
  shard phase (start first wave → run each running shard → next wave auto-promotes) for tests / a manual
  trigger. No schema change.
- Verified by a new `FederatedPyramidServiceIT` scenario (8-player / 4-shard federation, seats=2): draining
  runs all four 2-player pyramids to a champion, every shard ends COMPLETED with a winner, and the federation
  reaches AWAITING_FINAL. Surefire suite green (1090).

### 🔺 Federated pyramid — slice 2: registration + wave fill
- `FederatedPyramidService` orchestrates the fill phase. `createFederation(...)` lays out the federation +
  shard skeleton from a `FederatedPyramidPlan` (seats/hands snapshotted from the pyramid config so shards
  match the engine; shard 0 opens, the rest PENDING; each shard round-robin pinned to a node-group).
  `register(...)` assigns a player to the open fill shard (lowest-index REGISTERING with capacity), flips a
  full shard to **READY** and opens the next PENDING shard — idempotent per (federation, player), rejected
  once every shard is full. The heavy `promoteShards(...)` materializes READY shards into running child
  PYRAMID tournaments (create → seed registrations → start) up to the **wave concurrency cap**
  (`app.tournament.federated-max-concurrent-shards`, default 8), decoupling materialization from the fast
  registration path. New `pyramid_federation_registrations` table (changeset 17, unique
  `(federation_id, player_id)`), a `READY` shard status, and `federated-max-concurrent-shards` /
  `federated-node-group-count` config.
- Verified by `FederatedPyramidServiceIT` (seats=2 → an 8-player/4-shard federation): layout, in-order
  wave fill flipping shards to READY, capped promotion (cap 2 → 2 RUNNING with seeded child tournaments, 2
  left READY, federation → SHARDS_RUNNING, second promote starts nothing), idempotent registration + full
  rejection. Changeset 17 runs clean on a fresh Postgres (`ddl-auto=validate`); surefire suite green (1090).

### 🔺 Federated pyramid — slice 1: model + sharded decomposition
- New very-large "federated" pyramid tournament type: a field (e.g. 1,000,000) is split into shards of up to
  `shardSize` (e.g. 10,000) that fill and run in waves — each shard an ordinary pyramid down to one winner —
  and the shard winners later meet in an admin-scheduled final. This slice lays the foundation (play-money,
  flag-gated, no orchestration yet): `FederatedPyramidPlan` (pure) decomposes the field reusing the existing
  `PyramidBracket` — 1,000,000 / 10,000 / seats=10 → **100 shards** of a 10k pyramid (4 levels) feeding a
  **100-finalist** final (2 levels). `PyramidFederation` + `PyramidFederationShard` entities (status enums,
  `@Version`, unique `(federation_id, shard_index)`, nullable `registration_deadline` = indefinite, nullable
  `node_group` for physical shard pinning, `final_scheduled_start`) + repositories + a
  `app.tournament.federated-pyramid-enabled` flag (+ default shard size). Liquibase changeset 16 (two tables).
- Verified: `FederatedPyramidPlanTest` (canonical 1M case + ceil rounding + small example + validation) and
  `PyramidFederationRepositoryIT` (defaults, ordered shards, status counts, find-by-tournament, unique index)
  green; changeset 16 runs clean on a fresh Postgres with `ddl-auto=validate` (app starts, entities match);
  surefire suite green (1090). Decided (this epic): shards fill in **waves** / by capacity; each shard pinned
  to a **physical node-group**; the final waits on a **strict barrier of all winners**, then an **admin sets
  its start time and e-mails the finalists** (reusing `TournamentNotificationService`); the registration
  deadline can be **indefinite**. Next slices: registration + shard assignment + wave start; shard run →
  winner capture; finalists barrier + scheduled final + run; REST/UI; real money.

### 📈 Load scenario: cluster × N WebSocket clients (`websocket-cluster.js`)
- New k6 scenario `load/k6/websocket-cluster.js` + runner `run-ws-cluster.sh` / `.ps1`: opens a fleet of
  long-lived **STOMP-over-WebSocket** subscribers through the round-robin LB, holds them open, and reports the
  per-node split (`websocket_sessions_local` gauge + heap scraped from each node's `/actuator/prometheus`).
  This targets the dimension a single instance can't carry — a 10k-player tournament is a concurrent-WS /
  memory problem (~1–2 MB heap per session), not a CPU one — and measures that the cluster spreads the
  sessions. Talks raw STOMP to the non-SockJS `/ws` endpoint; auth optional (the interceptor allows anonymous
  read-only, perfect for a hold-N-subscribers test); `SEED=1` seeds a 900-bot tournament so subscribers get
  real broadcasts. Knobs: `CONNECTIONS`, `RAMP`, `HOLD`, `TOURNAMENT_ID`, `TOKEN`.
- `docker/nginx/scale.conf` tuned for a large fleet (`worker_connections 32768`, `worker_rlimit_nofile`,
  3600s WS read/send timeouts so held connections aren't reaped). Validated: k6 compiles the script
  (`k6 inspect`), runner passes `bash -n`. **Honest scope:** the harness is the instrument; an actual
  sustained 10k run needs a sized cluster (≥4–8 nodes) + PgBouncer + a multi-host generator — that ops run is
  the remaining exercise, distinct from the code being ready. Documented in `load/k6/README.md`.

### 🔺 Buy-up pyramid — slice 7: player REST + "tickets" UI (epic complete)
- New player-facing `PyramidBuyoutController` (`/v1/tournaments/{id}/pyramid/...`): `GET …/tickets` lists the
  buyable higher-level seats (level, seat index, price, asset) and `POST …/buy-seat` `{level, seatIndex}` buys
  one for the authenticated user (charges the wallet at the seat price, which replaces the flat buy-in). DTOs:
  `BuyoutTicketResponse`, `PyramidSeatPurchaseResponse`, `BuyPyramidSeatRequest`; the service `BuyoutTicket`
  now also carries the paying asset. `TournamentDetailResponse` gained `pyramidBuyUpEnabled` so clients can
  gate the UI.
- Frontend: a `PyramidBuyUpPanelComponent` ("🎟️ Buy a higher-level seat") on the tournament lobby lists each
  buyable seat with its computed price and a **Buy** button, shown only to a registered player while a buy-up
  pyramid is REGISTERING. A purchase toasts the result and reloads the tournament. New
  `TournamentPyramidService` wraps the two endpoints; `pyramidBuyUpEnabled`/`type` are mapped into the
  tournament model.
- Verified end-to-end by `PyramidBuyoutControllerIT` (HTTP → controller → service → wallet → DB: tickets
  listed, seat bought, wallet charged the 200 price, buy-out persisted). ArchUnit green (the DTO maps in the
  controller, not the `dto` package, to avoid a dto→service cycle). Backend surefire suite green (1086);
  frontend `ng build` + eslint clean. **This closes the buy-up pyramid epic (slices 1–7).**

### 🛎️ Admin: postpone an under-filled tournament + e-mail its registrants
- New admin action `POST /admin/tournaments/{id}/reschedule` (body `{ "startAt": Instant }`) moves an
  under-filled tournament's start to a later time. `TournamentService.rescheduleIfUnderfilled` guards it:
  REGISTERING only, the new time must be in the future, and it is rejected (409) once the required
  head-count is reached — `maxPlayers` for a full-required tournament, otherwise `minPlayers`. A non-future
  time is a 400. The `requireFull` flag is preserved.
- Registrants are then notified by e-mail via the new `TournamentNotificationService`, reusing the existing
  flag-gated `EmailService` (`app.mail.enabled`) infrastructure with a new "tournament rescheduled" template.
  A registrant's address is resolved by treating the registration's `playerId` as the owning `User` id
  (which it is for real-money entrants registered via the wallet bridge); bots / play-money randoms with no
  user or no e-mail are skipped. Returns the number actually e-mailed.
- Verified by `TournamentRescheduleIT` (full Spring context): under-filled → postponed and exactly the two
  real registered users e-mailed (the bot skipped); enough players → 409 and no e-mails; past time → 400.
  Surefire suite green (1086); the modified `AdminTournamentController` / `TournamentWalletService` beans
  boot cleanly in every full-context IT. **SMS and an admin UI button are documented follow-ups** (the
  `users` table has no phone column and no SMS gateway is wired — both would need new infrastructure).

### 🔺 Buy-up pyramid — slice 6: refund buy-outs on cancellation
- `TournamentWalletService.cancelAndRefund` is now buy-up-aware: a player who bought a higher-level seat paid
  the seat **price** (which replaced the flat buy-in), so on cancellation they are made whole with that price,
  while every other registrant gets the flat buy-in back. The buy-out refund uses a distinct idempotency key
  (`tbuyup-refund:…` vs. `trefund:…`), so a buyer is never double-refunded and a re-run never double-pays.
- This also covers the **"tournament never fills"** case: an under-filled buy-up pyramid is cancelled through
  this same path (manual admin cancel or the scheduled-start under-fill sweep), so buyers get their seat price
  back automatically. Verified by a new `PyramidBuyoutServiceIT` scenario (buyer refunded 200 = seat price, a
  plain registrant refunded 20 = buy-in) + the unchanged `TournamentWalletServiceIT` / scheduled-start tests.
  Full suite green (1086).

### 🔺 Buy-up pyramid — slice 5b: fixed-bracket engine advancement
- `PyramidTournamentService.advanceToNextRound` now branches on `pyramidBuyUpEnabled` to a new
  `advanceBuyUpToNextRound`: when a round ends it closes the old tables, computes the next round's seats as the
  active survivors **plus the buyers whose bought level equals the new round** (buyers above the new round stay
  deferred until their level), skips levels that nobody reaches, creates the next level's tables (tagged
  `bracketLevel = newRound`, the last one becomes the final table when no one is deferred above), round-robin
  seats the players, and advances `pyramidRound` to the new level. **The branch is fully flag-gated — the normal
  dynamic pyramid follows the existing path unchanged** (`PyramidAdvanceRoundIT` still green).
- Verified end-to-end by a new H2 IT (`PyramidBuyUpRunIT`): 50 floor players play level 1, two level-2 buyers
  enter at level 2, the bracket runs to a single champion, and a buyer is resolved (champion or eliminated).
  Players are real-money (funded + bridge buy-in) **and** bot-named so the engine can auto-play them — closing
  the buy-up-needs-wallet vs. pyramid-needs-bots tension. No schema change in this slice. Full suite green.

### 🔺 Buy-up pyramid — slice 5a: fixed-bracket seating wired into start
- `TournamentStartService.completeStart` now branches on `pyramidBuyUpEnabled`: a buy-up pyramid seats the
  floor (registered non-buyers) on level-1 tables per `PyramidSeatingPlanner`, while buyers stay PLAYING but
  unseated until their level's round (advancement = slice 5b). Only the level-1 tables holding a floor player
  are created, each tagged `TournamentTable.bracketLevel = 1` (changeset 15, nullable column). **The branch is
  fully flag-gated — every other tournament type and the normal pyramid follow the existing path byte-for-byte**
  (the unchanged `PyramidAdvanceRoundIT` + start unit tests still pass).
- Verified by an H2 IT (floor seated, buyer elevated/unseated, tables at bracket level 1) and changeset 15 on
  a fresh Postgres (17 changesets, `bracket_level` added, `ddl-auto=validate` passes). Full suite green (1086).

### 🔺 Buy-up pyramid — slice 4: fixed-bracket start seating plan
- `PyramidSeatingPlanner` computes the deterministic start seating for a buy-up pyramid: registered
  (non-buying) players fill the level-1 floor in registration order **skipping the seats inside closed
  (bought) sub-pyramids**, and each buyer is placed directly at the level/seat they bought (entering above the
  floor). Over-capacity (more registered than open floor seats) is rejected. Pure + unit-tested (no buy-outs,
  a level-2 buy-out skipping [0,10), multiple buy-outs, over-capacity). This is the seating "brain" that the
  next slice wires into the live start/advance engine (switching the buy-up variant from the dynamic pyramid
  to the fixed bracket). Full suite green (1086).

### 🔺 Buy-up pyramid — slice 3: seat purchase (price replaces buy-in)
- `PyramidBuyoutService` lets a registered player buy a higher-level seat before start. `availableTickets`
  lists the buyable seats per level with their price (the "tickets" the player UI will show); `buySeat`
  validates the rules and records the buy-out. **The seat price replaces the flat buy-in** — the base buy-in
  is refunded and the sub-tree price (`seatsPerTable^(L-1) × buyIn`) charged, so the net cost is the seat
  price. Rules enforced: buy-up + real-money + REGISTERING, player registered, level/seat in range, total cap
  (`app.tournament.pyramid-max-buyouts`, default 10), one per player (DB), seat not taken (DB), and the
  sub-pyramid empty — its level-1 range above the registration frontier and not overlapping another buy-out
  (the "11 occupied → 98 buyable" rule). Verified by an H2 IT (economics, frontier, overlap, one-per-player,
  registered-only) + `PyramidBracket` sub-tree range/overlap unit coverage. Full suite green (1082).

### 🔺 Buy-up pyramid — slices 1–2: bracket pricing core + persistence model
- `PyramidBracket` (slice 1) models the fixed buy-up pyramid tree — tables per level (1000→100→10→1 at 10
  seats/table), buyable seats per level (= feeder tables), sub-tree level-1 seats, and the buy-out price
  (`seatsPerTable^(L-1) × buyIn`). Pure + unit-tested vs the spec example.
- Persistence (slice 2): a `pyramidBuyUpEnabled` flag on tournaments + a `PyramidBuyout` entity/repository
  (tournament, buyer, level, seat index, price, asset) with DB uniqueness enforcing the rules — **one buy-out
  per player** and **one buyer per seat** (changeset 14). Verified on H2 (persistence + both uniqueness
  violations) and a fresh Postgres (16 changesets, the table + 2 unique constraints, `ddl-auto=validate`
  passes). Full suite green (1082). The buy-seat API, fixed-bracket seating, engine wiring and the player
  "tickets" UI are the next slices (TODO).

### 💸 Auto-cancel under-filled scheduled tournaments with buy-in refund
- A scheduled (non-full-required) tournament that is still below `minPlayers` at its slot is now **cancelled
  and its real-money buy-ins refunded**, instead of being left REGISTERING. `TournamentWalletService
  .cancelAndRefund` credits each registrant's buy-in back (new `TOURNAMENT_REFUND` ledger type, changeset 13
  widens the `wallet_ledger_entries.type` CHECK) and marks the tournament CANCELLED — all in one transaction,
  idempotent per (tournament, user) so a re-run never double-refunds. Play-money tournaments just cancel.
  Flag-gated (`app.tournament.cancel-underfilled-scheduled`, default true); full-required tournaments still
  postpone a day rather than cancel.
- Verified: scheduler unit tests (cancel+refund when under min / leave open when disabled), an H2 IT (two
  buy-ins refunded, balances restored, status CANCELLED; refund idempotency), and changeset 13 on a fresh
  Postgres (14 changesets, the CHECK now lists `TOURNAMENT_REFUND`, `ddl-auto=validate` passes). Full suite
  green (1076).
- **Manual admin cancel refunds too**: `POST /v1/admin/tournaments/{id}/cancel` now goes through
  `cancelAndRefund`, so cancelling a real-money tournament from the admin UI returns every registrant's buy-in
  (same idempotent primitive). Full suite green (1076).

### ⏰ Time-of-day tournaments that start when full (or postpone a day)
- A tournament can be pinned to a **time-of-day slot** that starts only if the table is **full** at that time;
  if under-filled, the slot **postpones to the next day**. `POST /v1/admin/tournaments/{id}/schedule-daily
  {timeOfDay, requireFull}` computes the first slot via `TournamentSlotPlanner`, leaving at least the
  configured **registration runway** (`app.tournament.scheduled-start-runway-hours`, default 3) — if today's
  slot is closer than the runway, the next day's slot is used. The slot's time-of-day is interpreted in
  `app.tournament.scheduled-start-zone` (default UTC).
- `Tournament.requireFullToStart` (changeset 12) drives the poller: at a due slot a full table starts
  (`registered >= maxPlayers`), otherwise the slot moves +24h and the tournament stays REGISTERING. Verified
  with a pure planner unit test (runway boundary, passed-slot, zone offset), scheduler unit tests (start when
  full / postpone when not), an H2 IT (time-of-day round-trip + postpone), and changeset 12 on a fresh
  Postgres (13 changesets, `ddl-auto=validate` passes). Full suite green (1075).
- **Admin UI**: `/admin/tournaments/{id}` now has a Schedule panel (REGISTERING only) — pick an exact
  date/time or a daily time-of-day with a *start only if full* toggle — wired to the new endpoints. The
  tournament detail response now carries `scheduledStart` + `requireFullToStart` so the current schedule is
  shown. Frontend builds clean.

### ⏰ Scheduled tournament auto-start
- Tournaments can now start at a **scheduled time**, not just manually or (for Sit & Go) on a full table.
  `Tournament.scheduledStart` (changeset 11, nullable) + `POST /v1/admin/tournaments/{id}/schedule {startAt}`
  set the time while a tournament is REGISTERING. `TournamentScheduledStartService` polls (flag-gated
  `app.tournament.scheduled-start-enabled`, default off) and auto-starts due tournaments **once minPlayers is
  met**; under-filled ones are left REGISTERING and logged (auto-cancel + buy-in refund is a separate
  follow-up). Cluster-safe to run on every node — `startTournament` guards on status + the entity's
  optimistic-lock version, so a concurrent double-start has one winner and the loser's tx rolls back.
- Verified: a unit test (dispatch + min-players gate + fault isolation + disabled-is-inert), an H2 IT (the
  due-query returns only REGISTERING tournaments past their time; future/manual excluded; column round-trips),
  and changeset 11 on a fresh Postgres (12 changesets, `ddl-auto=validate` passes). Full suite green (1068).

### 🎲 Cash games (ring tables) — slice 1: table config model
- First slice of the cash-game epic (real-money ring tables, distinct from tournaments). `CashTable` persists a
  ring-table definition — stakes (SB/BB), buy-in bounds, seat count, settlement asset and rake (basis points +
  cap) — with a `CashTableRepository` (+ `findByActiveTrueOrderBySmallBlindAsc`). Amounts are asset major
  units, like the wallet. Liquibase changeset 10 creates `cash_tables` (Postgres-only; H2 regenerates from the
  entity), verified on a fresh Postgres (11 changesets, `ddl-auto=validate` passes) + an H2 persistence test.
  Full suite green (1064). Remaining cash-game slices (seat/buy-in/cash-out/rake/engine/API/lobby) tracked in
  TODO.md.

### 🔁 Withdrawal reconcile scheduler + on-chain ERC-20 verification (ETH coordinator follow-ups)
- `WithdrawalReconcileScheduler` periodically scans BROADCAST withdrawals and dispatches each to its chain
  coordinator (ETH/ERC-20 or BTC) to reach CONFIRMED (or FAILED on an ETH revert) without a manual poke. Each
  `reconcile` is idempotent (terminal states are no-ops, `@Version` guards races), so it is safe on every node
  in a cluster — same discipline as the KYC retention sweep. Flag-gated
  (`app.payments.withdrawal-reconcile-enabled`, default off; interval `…-reconcile-interval-ms`). A failing
  reconcile is logged and the sweep continues; assets with no coordinator are skipped. Unit-tested with mocked
  coordinators (dispatch-by-asset, fault isolation, disabled-is-inert).
- **On-chain ERC-20 verification**: a new `geth --dev` IT deploys a minimal real ERC-20 (constructor mints to
  the treasury), then runs a full USDT_ERC20 withdrawal — the coordinator assembles the `transfer(...)` tx,
  signs it offline, broadcasts, reconciles to CONFIRMED — and asserts the recipient's on-chain `balanceOf`
  moved by exactly 1e6 units. This lifts the ERC-20 path from vector-only to on-chain-verified (catches
  gas-limit, contract-address and decimals wiring that calldata vectors can't). Full suite green (1064).

### ₿ Online BTC (P2WPKH) withdrawal coordinator (UTXO select → offline-sign → broadcast → confirm)
- The BTC counterpart of the ETH coordinator. `BtcRpcClient` (pure `RestClient` + Jackson + HTTP-Basic, no
  bitcoinj) scans the UTXO set for the treasury address (`scantxoutset` — no wallet import), broadcasts
  (`sendrawtransaction`), and reads confirmations (`getrawtransaction`). `BtcWithdrawalCoordinator` selects
  UTXOs, sizes the fee from a configurable sat/vByte rate, computes change (dust → fee), and assembles the
  **unsigned** P2WPKH tx for the offline signer; then broadcasts the signed raw tx and reconciles
  confirmations → CONFIRMED. `BtcScript` maps the network to its bech32 hrp (mainnet `bc` / testnet `tb` /
  regtest `bcrt`) and decodes a P2WPKH address to its `scriptPubKey`.
- **The private key never touches the server.** Signing (BIP-143) and the witness-tx serialization
  (`BtcTxSerializer`, BIP-144) live in **test sources** — the server only assembles the unsigned tx and relays
  the opaque signed hex. Admin endpoints: `GET …/withdrawals/{id}/btc-unsigned`, `POST …/btc-broadcast`,
  `POST …/btc-reconcile`, `GET …/btc-confirmation`. Flag-gated (`app.payments.btc-rpc-enabled`, default off).
- **Verified against a real `bitcoind -regtest` node** (Testcontainers): fund the treasury address from the
  node wallet, select the UTXO + fee on the server, BIP-143-sign offline and serialize the witness tx,
  broadcast, mine, reconcile to CONFIRMED, and assert the recipient received exactly the withdrawn amount
  on-chain. Plus a `BtcScript` unit test vs the canonical BIP-173 address. Full suite green (1061).
- Honest scope: native-SegWit P2WPKH end-to-end on-chain. Legacy/Taproot recipient scripts, multi-address
  treasuries, `estimatesmartfee`, and a reconciliation scheduler are follow-ups; TRON is a separate slice.

### ⛓️ Online ETH/ERC-20 withdrawal coordinator (assemble → offline-sign → broadcast → confirm)
- The online half of an ETH/ERC-20 withdrawal, the piece the air-gapped signer can't do: `EthRpcClient` (a
  pure `RestClient` + Jackson JSON-RPC client, no web3j) reads live node state (`eth_chainId`, `eth_gasPrice`,
  `eth_getTransactionCount`, `eth_getBalance`, `eth_getTransactionReceipt`) and broadcasts via
  `eth_sendRawTransaction`. `EthWithdrawalCoordinator` assembles the **unsigned** tx (native ETH or an ERC-20
  `transfer`), broadcasts the raw tx the offline signer returned, and reconciles the receipt into the
  withdrawal state machine (→ CONFIRMED once it has the configured confirmations, → FAILED on revert).
- **The private key never touches the server.** The coordinator only reads the chain and relays bytes; signing
  stays offline. `EthAbi` (ERC-20 `transfer`/`balanceOf` calldata + hex/quantity helpers) is plain encoding,
  no key material. Admin endpoints: `GET …/withdrawals/{id}/eth-unsigned`, `POST …/eth-broadcast`,
  `POST …/eth-reconcile`, `GET …/eth-confirmation`. Flag-gated (`app.payments.eth-rpc-enabled`, default off).
- **Verified against a real `geth --dev` node** (Testcontainers): fund a treasury address from the unlocked
  dev account, assemble the unsigned tx from the node, EIP-155-sign it offline (test-sources signer),
  broadcast, reconcile to CONFIRMED, and assert the recipient's on-chain balance moved by exactly the
  withdrawn amount. Plus unit tests for the ABI/quantity encoding and the JSON-RPC envelope (fake node). Full
  suite green (1057).
- Honest scope: native ETH is verified end-to-end on-chain; the ERC-20 path (calldata + `balanceOf` decode) is
  unit-verified, with a token-deploy on-chain IT and a BROADCAST→CONFIRMED reconciliation scheduler as
  follow-ups. BTC (bitcoind regtest) and TRON coordinators are separate slices.

### 🔁 KYC re-encryption sweep (key rotation / config→KMS migration)
- `POST /v1/admin/wallet/kyc/re-encrypt` (ADMIN) re-encrypts every KYC document under the **currently-active
  key/provider**: decrypt with the key the document recorded, re-encrypt with the active one, overwrite in
  place, and update its `encryption_key_id`. Returns `{reEncrypted, skipped, total}`.
- Two use cases: after rotating config keys it re-keys documents off the retired key (so the old key can be
  dropped from the ring), and when migrating to KMS the active `KmsKycKeyProvider` re-wraps each document with
  a fresh data key. To bridge providers, the sweep **falls back to the config keyring** to decrypt ids the
  active provider can't resolve (so config→KMS works with the old keyring still present).
- Safe by construction: a document already on the active key is **skipped** (idempotent — a second run does
  nothing), and an encrypted document is **never silently downgraded to plaintext** if encryption is now
  disabled. Verified on H2: an old-key (`k1`) document migrates to the active key (`k2`), still decrypts, the
  on-disk bytes change, and a re-run is a no-op. Full suite green (1049).

### 🔐 Live AWS-KMS-backed KYC key provider (envelope encryption, no AWS SDK)
- `KmsKycKeyProvider` is a drop-in `KycKeyProvider` (selected by `app.payments.kyc-key-provider=kms`) that uses
  **AWS KMS envelope encryption**: each upload gets a fresh AES-256 data key from `GenerateDataKey`, the
  plaintext key encrypts the video and is discarded, and the **CMK-wrapped data key** is recorded as the
  document's key id (`kms:` + base64(CiphertextBlob)). Reads unwrap it with `Decrypt`. The raw key never lives
  in config — only the CMK id + IAM credentials do.
- Talks to the KMS JSON API directly over a `RestClient`, **SigV4-signed via the existing `AwsV4Signer`** (no
  AWS SDK → the offline build adds no dependency), the same approach as the S3/MinIO storage backend.
- The envelope model evolved the `KycKeyProvider` seam to mint a per-document `DataKey` (key + id);
  `ConfigKycKeyProvider` keeps the config-keyring behaviour. `kyc_documents.encryption_key_id` widened to
  `varchar(1024)` (changeset 09) to hold the wrapped data key.
- Verified against a **fake in-test KMS** (`com.sun.net.httpserver`): envelope round-trip, SigV4 `Authorization`
  + `X-Amz-Target` on the wire, non-KMS key ids rejected, missing CMK id rejected. Migration caveat documented:
  switching an environment to KMS leaves pre-existing config-encrypted documents unreadable (re-encrypt first).
- Verified on H2 + a fresh two-node Postgres cluster (10 changesets, `encryption_key_id varchar(1024)`,
  `ddl-auto=validate` passes on both). Full suite green (1049).

### 🔐 KYC encryption key rotation (key-id per document) + AV scan of uploads
- KYC videos can now be encrypted under a **versioned keyring** (`app.payments.kyc-encryption-keys.<id>` +
  `app.payments.kyc-active-key-id`) instead of a single static key. Each document records the **key id** it was
  encrypted with (`kyc_documents.encryption_key_id`, changeset 08), so rotating keys is just *add a new key →
  flip the active id* — new uploads use the new key while older documents stay decryptable under their original
  key. The legacy single key (`kyc-encryption-key`) is still honoured, exposed internally as key id `default`.
- New `KycKeyProvider` seam (`ConfigKycKeyProvider` is the config-backed impl) is the **drop-in point for a real
  KMS**: a future KMS-backed provider plugs in without touching callers (reuses the existing `AwsV4Signer`/SigV4
  for AWS KMS).
- **Anti-virus scan** of uploads via a ClamAV daemon (clamd `INSTREAM` over a raw socket, no client dependency).
  Enabled with `app.payments.kyc-av-scan-enabled=true`; an infected upload is rejected with **HTTP 422
  (`KYC_MEDIA_REJECTED`)** and an unreachable/erroring clamd **fails closed** (the upload is not stored). Default
  is a no-op scanner, so behaviour is unchanged unless enabled.
- Verified on H2 + a fresh two-node Postgres cluster (9 changesets, `encryption_key_id varchar(64)`,
  `ddl-auto=validate` passes on both nodes). Full suite green (1045). Live AWS-KMS-backed provider is a
  documented follow-up.

### 🔑 BTC Taproot key-path signer — BIP-340 Schnorr + BIP-341 tweak (pure Java)
- `Schnorr` implements **BIP-340** signing + verification over secp256k1 (tagged hashes, even-Y nonce, x-only
  keys), and `TaprootSigner` applies the **BIP-341** key-path tweak
  (`(d_even + tagged_hash("TapTweak", P_x)) mod n`) and Schnorr-signs the sighash — the offline half of a
  Taproot key-path spend. Reuses `EthKeys`' curve math; no new dependency.
- Verified byte-for-byte against the **official BIP-340 test vector** (secret key 3) and against an
  independent reference for the Taproot tweak: `outputKeyX(1)` and the key-path signature match, and the
  signature verifies against the output key. Full suite green (1034).
- Test-sources only (never ships in the server jar). As with the other signers, the **BIP-341 sighash** (which
  commits to all spent prevout amounts + scriptPubKeys) is built online from the PSBT/node — the offline tool
  only tweaks + signs. This completes the offline signing primitives for ETH/ERC-20, BTC (P2PKH/P2WPKH/Taproot)
  and TRON; the remaining work is online PSBT/raw_data assembly + broadcast.

### 🔑 Air-gapped BTC (P2WPKH) + TRON signers (pure Java, no node)
- Extend the offline signer to Bitcoin and TRON. `BtcSigner` computes the **BIP-143 P2WPKH sighash** and a
  strict-DER ECDSA signature (RFC-6979 + low-s) — exactly the value a watch-only PSBT needs finalised into the
  input witness. `TronSigner` produces the **65-byte recoverable signature** (`r‖s‖recId`) over
  `SHA-256(raw_data)` (the TRON tx id).
- Verified against independent implementations: the BIP-143 sighash reproduces **embit** byte-for-byte, and
  both the BTC and TRON signatures reproduce **eth-account**'s ECDSA over the same digests. (embit picks a
  different — but equally valid — RFC-6979 nonce; the network accepts any valid low-s signature.)
- All signing code stays in **test sources** (never ships in the server jar). Honest scope: PSBT
  parse/finalise + UTXO selection (BTC) and `raw_data` assembly from a recent block ref (TRON) are the online
  coordinator's node-dependent job — the offline tool only signs. Full suite green (1031). BTC Taproot
  (key-path tweak) signing and the broadcast step remain follow-ups.

### 📖 Moderator guide (`docs/MODERATOR_GUIDE.md`)
- A runbook for moderators/admins covering the whole crypto wallet: KYC review (view video → VERIFIED/REJECTED
  → GDPR erase), manual withdrawal approval (PENDING_APPROVAL → approve/reject), the air-gapped offline-signer
  procedure (export intent → sign offline → broadcast → record), deposit-address pool monitoring/refill, plus
  security do/don't and a config-flag reference. Endpoints documented at `/api/v1/...`.

### 🔑 Air-gapped Ethereum withdrawal signer (pure Java, no node)
- The offline side of the withdrawal handoff: a pure-Java **EIP-155 transaction signer** that needs no node —
  RLP encoding (`Rlp`), RFC-6979 deterministic ECDSA over secp256k1 with low-s/EIP-2 (`EcdsaSecp256k1`,
  reusing `EthKeys`' curve math + `Keccak256`), and `EthTransactionSigner` that builds + signs a legacy tx
  into a broadcastable raw-tx hex (native ETH **and** ERC-20 `transfer`, i.e. USDT-ERC20).
- CLI `OfflineWithdrawalSigner` (the air-gapped tool): takes the exported withdrawal intent + chain params
  (nonce/gas/chainId) + the seed, derives the signing key by index (same KDF as the deposit pool), and prints
  a signed raw transaction to broadcast via any node's `eth_sendRawTransaction` — after which the tx hash is
  recorded back via `POST /v1/admin/wallet/withdrawals/{id}/broadcast`.
- **All signing code lives in test sources** — it touches private keys and must never ship in the online
  server jar (same principle as the offline address generator). Verified against ground-truth vectors from an
  independent implementation (eth-account): the canonical EIP-155 vector, a recId=1 native transfer, and an
  ERC-20 transfer all reproduce byte-for-byte; full suite green (1027). BTC PSBT signing + broadcasting via a
  node remain follow-ups (need a node/RPC).

### 💸 Withdrawal offline-signer (PSBT) handoff
- Completes the offline-pool withdrawal path. After a moderator **approves** an offline-pool withdrawal it
  stays `APPROVED` (the provider can't broadcast in-process). Two admin endpoints bridge to the air-gapped
  signer: `GET /v1/admin/wallet/withdrawals/{id}/unsigned` exports the signer-ready **intent**
  (`WithdrawalSigningRequestDto`: asset, network, toAddress, amount), and
  `POST /v1/admin/wallet/withdrawals/{id}/broadcast` (`{txId}`) records the tx id the offline signer
  produced → `BROADCAST` (then the existing `withdrawal-status` webhook confirms/fails it).
- Both steps require an `APPROVED` request (`WalletService.withdrawalForSigning` / `recordBroadcast`, else
  409). **Honest scope**: the online server has no node/RPC, so it exports the *intent* (not a literal PSBT
  blob) — the air-gapped signer builds, signs and broadcasts the chain tx (PSBT for BTC, raw tx for
  ETH/TRON) with the seed + its own node. No schema change (reuses `markBroadcast`).
- Verified (`WithdrawalSigningHandoffIT`, provider=offline-pool): approve leaves it APPROVED → export intent →
  record broadcast → BROADCAST; export/record on a non-APPROVED request rejected. Full suite green (1023).

### 💸 Withdrawal limits + cooling period (AML controls)
- **Per-transaction** and **rolling-24h** withdrawal limits, configured per asset
  (`app.payments.max-withdrawal-per-tx.<ASSET>`, `…max-withdrawal-per-day.<ASSET>`; absent = no limit).
  `requestWithdrawal` rejects an over-limit request **before debiting** (`WithdrawalLimitExceededException`
  → HTTP 422 `WITHDRAWAL_LIMIT`); the daily total counts all of the user's non-reversed withdrawals for that
  asset in the last 24h.
- **Cooling period** (`app.payments.withdrawal-cooling-period-minutes`, default 0 = off): a moderator cannot
  approve a withdrawal until the delay since the request has elapsed (`WithdrawalCoolingPeriodException`) — a
  fraud-detection window before funds can leave.
- No schema change. Verified (`WithdrawalLimitsIT`): over-per-tx rejected without debit; the 24h limit counts
  prior pending withdrawals; approving inside the cooling window is blocked. Full suite green (1034).
  (Per the request, no two-moderator / 4-eyes rule.)

### 💸 Withdrawal moderation UI (`/admin/withdrawals`, Angular)
- Admin-guarded page listing withdrawals awaiting moderation with **Approve / Reject** (inline reason), and
  the offline-signer handoff for approved offline-pool withdrawals: **Export** (shows the signer-ready intent
  to sign with `OfflineWithdrawalSigner`) and **Broadcast** (record the tx id) → BROADCAST. `AdminWithdrawalService`
  wraps `GET /v1/admin/wallet/withdrawals` + approve/reject/unsigned/broadcast. Nav link 💸 Withdrawals (admins).
- Built and verified with `npm run build` (the new lazy chunk compiles). The page updates rows in place after
  each action.
- Backend: `GET /v1/admin/wallet/withdrawals` now takes an optional `?status=` filter; with no param it returns
  the **review set** (PENDING_APPROVAL + APPROVED), so approved-awaiting-broadcast withdrawals are visible
  **across sessions** (not just to the moderator who approved them). `WalletService.withdrawalsForReview`.

### 💸 Manual withdrawal approval (moderator gate)
- Flag-gated `app.payments.withdrawal-approval-required` (default **off** — immediate-broadcast behaviour is
  unchanged). When **on**, `requestWithdrawal` debits the balance and parks the request in
  **`PENDING_APPROVAL`** instead of broadcasting; a moderator then decides.
- `WalletService.approveWithdrawal(id, moderatorId)` → broadcasts via the provider (`BROADCAST`); for a
  provider that can't broadcast in-process (offline-pool) it stays `APPROVED`, awaiting the offline signer
  (PSBT handoff). `rejectWithdrawal(id, moderatorId, reason)` → `REJECTED` and reverses the debit
  (`WITHDRAWAL_REVERSAL`). Both act only on a `PENDING_APPROVAL` request (else 409); concurrent actions are
  guarded by the row's `@Version`.
- New `WithdrawalStatus` values `PENDING_APPROVAL`/`REJECTED` + audit columns `reviewed_by`, `reviewed_at`,
  `rejection_reason` (Liquibase changeset `07-withdrawal-approval`: widens the status CHECK + adds columns,
  Postgres-only; H2 regenerates from the entity). Admin API: `GET /v1/admin/wallet/withdrawals` (pending list),
  `POST .../{id}/approve`, `POST .../{id}/reject` (ADMIN; moderator id from the authenticated principal).
- Verified: request→PENDING_APPROVAL (debited), approve→BROADCAST, reject→REJECTED+reversal, action on a
  non-pending request rejected; full suite green (1023) + a fresh-Postgres cluster boots with all **eight**
  changesets applied and `ddl-auto=validate` passing on both nodes. The PSBT/offline-signer broadcast for the
  pool is the documented next layer.

### 🪪 KYC UI — player upload page + moderator review (Angular)
- **Player page** (`/kyc`): shows current KYC status and lets the user upload a verification video
  (`KycService` → multipart `POST /v1/wallet/kyc/document`).
- **Moderator page** (`/admin/kyc`, ADMIN-guarded): lists pending submissions, streams the selected user's
  video (fetched as a Blob so the auth interceptor attaches the token → object URL), and Approve / Reject /
  Erase (GDPR) actions (`AdminKycService`).
- Backend: new `GET /v1/admin/wallet/kyc/pending` (`KycVerificationService.listPending`, `KycPendingDto`) so
  the moderator page has a work-list. No schema change. Backend full suite green (1023). **The Angular code is
  not built in CI here (offline, no `node_modules`) — run `npm ci && npm run build` to compile it.**
- Navigation: a 🪪 **Verify** link (authenticated users → `/kyc`) and a 🪪 **KYC review** link (admins →
  `/admin/kyc`, in the existing admin block) added to the main nav.

### 🗄️ KYC media: S3/MinIO object storage backend
- KYC verification media can now live in **S3-compatible object storage** (AWS S3 / MinIO) instead of the
  local filesystem — `app.payments.kyc-storage-type=s3` + `s3-endpoint/s3-bucket/s3-region/s3-access-key/
  s3-secret-key`. Cluster-friendly (no shared volume needed). Default stays `filesystem`.
- New `KycStorage` abstraction with `FilesystemKycStorage` (default) and `S3KycStorage`; the latter talks S3
  over the existing RestClient signed with hand-rolled **AWS SigV4** (`AwsV4Signer`, HMAC-SHA256) — no AWS
  SDK, no new dependency. `KycVerificationService` now delegates store/load/delete to the backend (encryption
  + metadata unchanged). Path-style requests; the bucket is created lazily.
- Verified: `AwsV4Signer` reproduces the **official AWS SigV4 `get-vanilla` test vector** byte-for-byte; a
  Testcontainers **MinIO** round-trip (`S3KycStorageIT`) stores → loads → deletes through the real S3 API.
  Filesystem path unchanged (existing KYC ITs still green). Full suite green (1035).

### 🔒 KYC media: encryption at rest + GDPR retention/erasure
- **Encryption at rest**: KYC verification videos are encrypted with **AES-256-GCM** (new pure-JDK `KycCrypto`,
  `[12-byte IV][ciphertext+tag]` on disk) when `app.payments.kyc-encryption-key` (base64 AES key) is set; the
  key is never stored with the data, GCM detects tampering, and reads transparently decrypt for ADMIN review.
  A per-document `encrypted` flag (changeset `06-kyc-document-encryption`) lets the system read both plaintext
  and encrypted files (graceful key-rollout). The sha-256 is over the plaintext either way.
- **GDPR retention**: `KycRetentionScheduler` periodically deletes KYC media (file + metadata) older than
  `app.payments.kyc-retention-days` (default 30; 0 = never). Idempotent (`deleteIfExists`), so it is safe on
  every cluster node.
- **GDPR right-to-erasure**: `DELETE /v1/admin/wallet/kyc/{userId}/documents` removes all of a user's KYC
  media on demand.
- Verified: encryption round-trip (on-disk bytes ≠ plaintext, length = plaintext + 28; decrypts back),
  retention purge and erasure delete file + row; full suite green (1023) + a fresh-Postgres cluster boots with
  all **seven** changesets applied and `ddl-auto=validate` passing on both nodes.

### 🪪 KYC verification video upload (user + passport)
- Users can upload a **KYC verification video** (e.g. holding their passport) at
  `POST /v1/wallet/kyc/document` (multipart); uploading moves their KYC to `PENDING`. New
  `KycVerificationService` validates type (`video/*`) and size (`app.payments.kyc-max-upload-bytes`,
  default 50 MB), stores the **file bytes on disk** under `app.payments.kyc-storage-dir` (keyed by a random
  name — never the client filename), and persists only **metadata** in the DB (`kyc_documents`: content type,
  size, sha-256, storage key, uploaded-at) — no LOB columns.
- Moderators (ADMIN) review via `GET /v1/admin/wallet/kyc/{userId}/document` (streams the latest video) and
  decide with `POST /v1/admin/wallet/kyc/{userId}/decision` (`VERIFIED`/`REJECTED`). Multipart limits raised
  to ~50 MB (`spring.servlet.multipart.*`).
- Sensitive biometric PII: the bytes are served only to ADMIN; the on-disk name is opaque. **In a cluster the
  storage dir must be a shared volume** (documented) so any node can read what another received; object
  storage is a follow-up. Liquibase changeset `05-kyc-documents` (Postgres-only; H2 regenerates from the
  entity). Verified: full suite green (1023) + a fresh-Postgres cluster boots with all **six** changesets
  applied and `ddl-auto=validate` passing on both nodes.

### 💰 Watch-only deposit detection (address → user → credit)
- New ingestion path for the offline/watch-only pool: a node/indexer that scans the pooled addresses posts a
  detected deposit **keyed by address** (it doesn't know the user) to a new secret-guarded webhook
  `POST /internal/wallet/deposit-by-address` (`{asset, address, txId, amount, confirmations}`).
  `DepositIngestionService` resolves the owning user from the pool (`DepositAddressPoolService.assignedUser`)
  and credits **idempotently by tx id** via the existing `WalletService.creditOnChainDeposit`.
- **Min-confirmations gate**: credit is withheld until `app.payments.min-confirmations` (default 1) is reached
  (`PENDING_CONFIRMATIONS`); deposits to an unknown/unassigned address are ignored (`UNKNOWN_ADDRESS`), and a
  redelivered tx is a no-op (`DUPLICATE`). The endpoint always returns 200 with the outcome so the watcher
  need not retry. No schema change (config + existing tables).
- This complements the existing `POST /internal/wallet/deposit` (which is keyed by `userId`, for custodial
  gateways). Verified end-to-end: assigned-address credit, idempotency, confirmation gate, unassigned/unknown
  ignored; full suite green (1023). The actual chain watcher (node/indexer/explorer feed) is an external,
  documented follow-up.

### 💰 BTC Taproot (`bc1p…`) addresses for the offline pool
- The offline pool now also supports **Taproot** (P2TR, key-path-only, `bc1p…`). New pure-Java `TaprootKeys`
  implements BIP-341: x-only internal key from `d·G`, `lift_x` to the even-Y point, tweak
  `t = tagged_hash("TapTweak", P_x)`, output key `Q = P + t·G`, then **bech32m** (BIP-350) of `Q_x` as
  witness v1. `Bech32` was generalised to switch between bech32 (v0) and bech32m (v1+) by the witness
  version. The secp256k1 primitives in `EthKeys` (point add/mul, field/order constants) are now
  package-private so the crypto classes can share them — no new dependency.
- `OfflineDepositPoolGenerator` gains `--btc-style=taproot`; BTC import validation already accepts any of
  P2PKH / P2WPKH / P2TR via `BtcKeys.isValidAddress`. No schema change.
- Verified against an independent BIP-341 implementation (privkey 1 →
  `bc1pmfr3p9j00pfxjh0zmgp99y8zftmd3s5pmedqhyptwy6lm87hf5sspknck9`), bech32m encode/decode round-trip, and
  rejection of v0/cross-type input; full suite green (1023). Bitcoin deposit addresses now cover all three
  mainstream formats (legacy, SegWit, Taproot).

### 💰 BTC native SegWit (bech32 `bc1q…`) addresses for the offline pool
- The offline pool now supports **native SegWit v0 P2WPKH** (`bc1q…`) Bitcoin addresses alongside legacy
  P2PKH — bech32 addresses are cheaper to spend from and the de-facto modern standard. New pure-Java `Bech32`
  (BIP-173) encoder/validator + `BtcKeys.p2wpkhAddress` (same `HASH160`, bech32-encoded). No new dependency.
- `OfflineDepositPoolGenerator` gains `--btc-style=p2pkh|bech32` (default p2pkh); BTC import validation now
  accepts **either** format (`BtcKeys.isValidAddress`). No schema change.
- Verified against the canonical BIP-173 vector (`bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4`), encode/decode
  round-trip, and rejection of wrong-hrp/tampered/mixed-case input; full suite green (1019). Taproot
  (`bc1p…`, bech32m) remains a follow-up.

### 💰 BTC (legacy P2PKH) addresses for the offline pool
- The offline generator + pool now support **Bitcoin** (legacy P2PKH `1…` addresses). A BTC address uses the
  compressed secp256k1 public key (reusing `EthKeys.publicKeyBytes`), `HASH160 = RIPEMD160(SHA-256(pubkey))`,
  and Base58Check with the `0x00` version byte. Two new pure-Java primitives — `Ripemd160` (the JDK has no
  provider) and `BtcKeys` — plus shared `Base58.encodeChecked`/`verifyChecked` (now reused by `TronKeys`). No
  new dependency; SegWit/bech32 + Taproot are documented follow-ups (P2PKH is universally accepted).
- `OfflineDepositPoolGenerator` emits BTC addresses (`--asset=BTC`) under a separate derivation label, so the
  BTC key set never overlaps the ETH/TRON ones; import validation checks P2PKH version + Base58Check. No
  schema change — `BTC` was already a known asset.
- Verified: `Ripemd160` against canonical vectors; `BtcKeys` against an independent implementation (privkey 1
  → `1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH`) and the real Bitcoin genesis address; full suite green (1014).

### 💰 USDT-TRC20 (TRON) addresses for the offline pool
- The offline generator + pool now support **TRON (TRC-20)** alongside the Ethereum family — TRC-20 is the
  most common crypto deposit method on poker/casino sites. A TRON account reuses the same secp256k1 + Keccak-256
  primitives as Ethereum (`EthKeys.addressBytesFromPrivateKey`); only the encoding differs: new pure-Java
  `Base58` + `TronKeys` add the `0x41`-prefix Base58Check (`T…`) address using JDK SHA-256 — no new dependency.
- `OfflineDepositPoolGenerator` emits TRON addresses (`--asset=USDT_TRC20`) under a **separate derivation
  label** so the TRON key set never reuses the Ethereum keys. `DepositAddressPoolService` import validates
  TRC-20 addresses by Base58Check prefix + checksum (ETH-family still by EIP-55). No schema change —
  `USDT_TRC20` was already a known asset.
- Verified against an independent Base58Check implementation (privkey 1 → `TMVQGm1qAQYVdetCeGRRkTWYYrLXuHK2HC`)
  and the real mainnet USDT-TRON contract address; full suite green (1006).

### 💰 Offline-generated deposit-address pool (watch-only, no keys on server)
- New deposit provider `offline-pool` (`app.payments.provider=offline-pool`): deposit addresses are generated
  **offline** (private keys + seed never touch the server) and only their **public addresses** are imported;
  the server hands them out one-per-user-per-asset as players request a deposit address. On a server breach
  there are no spendable keys to steal — only watch-only addresses and balances.
- `DepositAddressPoolEntry` + `DepositAddressPoolService`: allocation is **idempotent** per (user, asset) and
  **concurrency-safe** (the next free address is row-locked `SELECT … FOR UPDATE` while claimed, so two
  simultaneous registrations cannot grab the same one); an exhausted pool is rejected
  (`DepositAddressPoolExhaustedException`), not silently double-assigned.
- Admin API (`/v1/admin/wallet`, ADMIN role): `POST /deposit-pool/import` loads a batch of public addresses
  (idempotent — duplicates skipped; ETH-family addresses validated by EIP-55 checksum) and
  `GET /deposit-pool/status` reports free/assigned counts per asset for low-watermark monitoring.
- Offline generator `OfflineDepositPoolGenerator` (lives in **test sources** so it is excluded from the
  production jar — a key generator must never ship inside the online service): derives a batch of ETH-family
  keypairs from a single seed (reusing the pure-Java `EthKeys`; backup = the one seed + index range) and
  writes `private.json` (keep offline) + `addresses.json` (the POST-ready admin import body).
- Liquibase changeset `04-deposit-address-pool` (Hibernate-generated DDL via `sqlFile`, Postgres-only; H2
  tests regenerate from the entity). Verified: full suite green + a fresh-Postgres cluster boots with all
  **five** changesets applied and `ddl-auto=validate` passing on both nodes. Withdrawal/sweep stays out of
  scope (spend pooled funds via an offline signer); the provider's `broadcastWithdrawal` is intentionally
  unwired. Initial assets: ETH + USDT-ERC20 (shared Ethereum address); USDT-TRC20 is a documented follow-up.

### 💰 Auto-payout on tournament finish
- When a **real-money** tournament completes, `TournamentPayoutListener` (`@EventListener` on the existing
  synchronous `TournamentCompleted` domain event) credits every in-the-money finisher's crypto wallet with
  their share of the prize pool. The listener stays thin (flag check + delegate); the work lives in
  `TournamentWalletService.payoutOnCompletion`, which loads the tournament, computes each finisher's share
  via `Tournament.cryptoPrizeForPosition(position)` (prize pool = crypto buy-in × registrations, split by the
  tournament's payout-structure percentages), and credits each through the idempotent `payout`.
- **Idempotent and best-effort**: a re-fired completion event does not double-credit (the per-(tournament,
  user) key is stored in the unique `external_tx_id`), and a single finisher's failed credit is logged
  without aborting the others. **No-op** for play-money tournaments and when `app.payments.enabled` is off.
- Tournament gains `crypto_buy_in_amount numeric(38,18)` / `crypto_buy_in_asset varchar(32)` (Liquibase
  changeset `03-tournament-crypto-buyin`, Postgres-only; H2 tests regenerate from the entity). Verified:
  full suite green + a fresh-Postgres cluster boots with all four changesets applied and `ddl-auto=validate`
  passing on both nodes.

### 💰 Wallet ↔ tournament buy-in/payout bridge
- Real-money tournament entry: `TournamentWalletService.buyIn` debits the player's crypto `WalletAccount`
  and registers them **in one transaction** (if registration fails — full / already in / not open — the
  debit rolls back), and `payout` credits the wallet with a prize. Both are **idempotent per
  (tournament, user)** (the idempotency key is stored in the unique `external_tx_id` column), so a repeated
  buy-in neither double-charges nor double-registers.
- New ledger types `TOURNAMENT_BUYIN` (debit) / `TOURNAMENT_PAYOUT` (credit) with `WalletLedgerEntry`
  factories and `WalletService.chargeBuyIn`/`awardPayout` (flag-gated by `app.payments.enabled`). Liquibase
  changeset `02-wallet-tournament-ledger-types` widens the `wallet_ledger_entries.type` CHECK on Postgres
  (H2 tests regenerate it from the entity). `POST /v1/tournaments/{id}/buy-in` is the entry point.
- In-game chips stay play-money; the bridge only moves the real crypto balance (debit on entry, credit on
  payout). Verified: full suite green + a fresh-Postgres cluster boots with all three changesets applied and
  `ddl-auto=validate` passing.

### 💰 Crypto wallet — on-chain deposits + KYC-gated withdrawals (flag-gated skeleton)
- New `wallet` subsystem (default **off**, `app.payments.enabled`): a real-money crypto balance separate from
  in-game chips. Entities `WalletAccount` (authoritative balance per user+asset, optimistic-locked),
  `WalletLedgerEntry` (append-only audit; unique on-chain `external_tx_id`), `WithdrawalRequest`
  (state machine), `KycRecord` (per-user KYC status). New Liquibase changeset `01-wallet.xml` (generated
  from the entities) keeps Postgres in sync so `ddl-auto=validate` passes.
- **Deposits** are credited **idempotently by tx id** — a duplicate provider webhook (or redelivery) for the
  same transaction is a no-op (the same exactly-once discipline as the game's `commandId`).
- **Withdrawals** are gated on KYC (`VERIFIED`) when `app.payments.kyc-required-for-withdrawal`: the KYC
  check, balance debit, ledger entry and request are written in one transaction, then broadcast via the
  provider; insufficient balance and missing KYC are rejected (409 `KYC_REQUIRED` / 422 `INSUFFICIENT_FUNDS`).
- Provider-abstracted: `CryptoPaymentProvider` (allocate address / broadcast). `MockCryptoPaymentProvider`
  is the default (`app.payments.provider=mock`); a real HTTP-gateway adapter `GatewayCryptoPaymentProvider`
  (NOWPayments/CoinsPaid-style REST, conditional on `app.payments.provider=gateway`) is included as an
  integration skeleton with a **network mode** (`app.payments.network` + `gateway-base-url`) so the full flow
  can run on a value-less **testnet/sandbox** before mainnet — only the provider config changes, the wallet
  logic is identical. A **self-custody** option (`app.payments.provider=eth-self-custody`,
  `SelfCustodyEthPaymentProvider`) shows deposit addresses can be generated with **no external provider** at
  all: pure-Java `Keccak256` + `EthKeys` (secp256k1 → EIP-55 address), verified against canonical vectors,
  derive a deterministic per-user ETH address from a configured master key (demo-grade; production = HSM +
  watch-only BIP-32 xpub; withdrawal signing/broadcast intentionally not wired — needs a signer + node).
  Inbound provider callbacks
  (`/internal/wallet/deposit`, `/internal/wallet/kyc-callback`) are guarded by a constant-time shared-secret
  header, mirroring the cluster internal endpoint.
- **Withdrawal lifecycle completion**: a provider callback (`/internal/wallet/withdrawal-status`) finalizes a
  broadcast withdrawal — `CONFIRMED` (idempotent) or `FAILED`, where failure marks it FAILED and credits the
  debited amount back via a `WITHDRAWAL_REVERSAL` ledger entry (also idempotent; a confirmed payout cannot be
  reversed). No schema change (the reversal type already exists).
- API: `/v1/wallet/{balances,deposit-address,kyc,kyc/submit,withdrawals}`. Tests: `WalletServiceIT`
  (idempotent deposit, KYC-blocked withdrawal, post-KYC success, insufficient funds, confirm, fail+reversal)
  + `WalletServiceDisabledTest`.
- Verified end-to-end: the cluster boots on a fresh Postgres with the wallet changeset applied and validated.

### 🏗️ Liquibase changelog squashed to a clean baseline
- The incremental changelogs `01`–`14` had accumulated two overlapping schema lineages and could never run
  clean on a fresh Postgres (duplicate tables `04`/`05`, duplicate columns `06`/`08`, and — the blocker —
  `07-tournaments.xml` renaming/dropping a **pre-`04`** `tournaments` shape, e.g. `DROP COLUMN
  active_game_id` that `04` never creates).
- Replaced them with a single **baseline** (`db/changelog/00-baseline.xml` + `baseline/schema-postgres.sql`)
  generated from the current JPA entities (Hibernate `ddl-auto=create` → `pg_dump`), so a fresh migration
  matches the entities exactly and `ddl-auto=validate` passes. The old `01`–`14` files are archived under
  `db/changelog/archive/` (no longer included by the master changelog). Postgres-only (tests use H2 +
  Hibernate, with Liquibase disabled).
- Added a `UNIQUE (player_name)` constraint on `player_statistics` in the baseline — completing the
  get-or-create robustness fix (one stats row per player name).
- **Liquibase is re-enabled on the runnable cluster** (`docker-compose.cluster.yml`, `ddl-auto=validate`);
  verified end-to-end: both nodes boot on a fresh Postgres, the baseline applies once (`databasechangelog`
  has a single row), and validation passes.

### 🐛 Robustness under load (surfaced by the scaling benchmark)
- **Game creation no longer 500s under concurrency.** `PlayerStatisticsService.getOrCreateStats` was a
  non-atomic find-or-create on `player_name` (no unique constraint), so concurrent game starts that share a
  player/bot name inserted duplicate rows and the next single-result `findByPlayerName` threw
  `NonUniqueResultException` → 500. The by-name lookup is now `findFirstByPlayerName` (tolerant, LIMIT 1),
  and `createNewGame` treats `startSession` as a best-effort side effect (a stats failure is logged, never
  fails game creation). Verified: 80 concurrent `POST /v1/poker/game/start` went from 80/80 → 500 to
  80/80 → 201.
- **Cross-node forwarding no longer turns game-level conflicts into 500s.** `ClusterInternalController`
  now translates the owner's exceptions (`IllegalState` → 409, `NoSuchElement` → 404, `IllegalArgument`
  → 400) instead of letting them become a 500, and `ClusterActionForwarder` distinguishes a **4xx** from
  the owner (a real game rejection — surfaced to the client, the caller does NOT re-claim the table) from a
  **connect/timeout/5xx** (owner unreachable — caller may re-claim once). Previously a "not your turn" on
  the owner became a 500 that the forwarder mistook for an unreachable owner.
- Tests: `ClusterInternalControllerTest` (secret + 200/409/404/400 translation), a `ClusterActionForwarder`
  4xx-vs-5xx case, and a `PokerGameService` test that game creation survives a throwing `startSession`.

### 🚀 Ops — Runnable two-node cluster (Phase 5)
- `docker-compose.cluster.yml`: two backend nodes behind an nginx load balancer on a shared Postgres +
  Redis, with all Phase 5 flags on (ownership, cross-node routing, failover takeover, fencing, ws-cluster).
  Each node gets a distinct instance id (its hostname) and a peer-reachable `CLUSTER_NODE_BASE_URL`; node 1
  runs Liquibase first, node 2 waits for it. Entry point http://localhost:8080 (LB); nodes also on 8081/8082.
- `docker/nginx/cluster.conf`: `ip_hash` stickiness (keeps a client's WebSocket + REST on one node),
  SockJS/STOMP upgrade proxying, long read/send timeouts, and a 403 on `/api/internal/**` so the
  node-to-node endpoints are never reachable from clients via the LB.
- `docs/cluster.md`: guided run + failover verification (inspect leases / node registry / active-table set
  / fencing tokens in Redis; observe cross-node forwarding; kill a node and watch the survivor take over).
- Verified end-to-end on Docker: both nodes boot on shared Postgres + Redis, register in the node registry
  (`truholdem:cluster:node:*`), the nginx LB (host **8090**, to coexist with the single-node compose on 8080)
  serves `/api/actuator/health`, and `/api/internal/**` is blocked (403) at the LB.

### 🐛 Fixes
- `db/changelog/10-tournament-scale-phase1.xml` used the wrong XSI namespace
  (`http://www.w3.org/2003/XMLSchema-instance` instead of `…/2001/…`), so Liquibase could not resolve the
  schema and failed to parse the changelog (`Cannot find the declaration of element 'databaseChangeLog'`).
  Corrected to `2001`. (This file is now archived by the changelog squash above; the runnable cluster runs
  Liquibase on the squashed baseline.)

### 🏗️ Architecture — Engine migration Phase 5 (fencing tokens)
- Optional fencing tokens (`app.cluster.fencing-enabled`, default off, requires ownership + hot-state) to
  stop a stale former owner from clobbering state after a lease handoff (e.g. a long GC pause during which
  its lease expired and another node took over).
- Each lease acquisition carries a monotonic token in Redis (`truholdem:cluster:fence:{id}`), bumped only
  when ownership changes hands and kept across renewals. `TableOwnershipService` issues the token via an
  enhanced Lua acquire script and tracks the token this node holds per table.
- The authoritative Redis hot-state write (`RedisGameStateStore.save`) becomes an atomic Lua compare-and-set
  that rejects a write whose token is behind the table's current token, throwing `StaleOwnershipException`.
  The held-token map naturally scopes fencing to owned tables — cache-population writes (after a DB read of
  a table this node doesn't own) carry no token and take the plain path, so they are never rejected.
- Postgres remains independently guarded by the `Game` `@Version` optimistic lock; Redis is authoritative.
- Tests: `RedisGameStateStoreTest` (plain vs fenced write, accept vs reject); `TableOwnershipServiceTest`
  (token issuance / failure); `TableOwnershipRedisIT` adds real-Redis monotonicity (renewal keeps the
  token; a takeover after lease expiry strictly increments it). Default (fencing off) behaviour unchanged.

### 🏗️ Architecture — Engine migration Phase 5 (split-brain safety: fail-closed)
- Optional fail-closed ownership mode (`app.cluster.fail-closed`, default off). The Redis lease normally
  fails open — a node that can't reach Redis assumes it owns its tables, which keeps a single node playable
  but lets a partitioned node double-own a table in a real cluster. With fail-closed on,
  `TableOwnershipService.acquire`/`isOwner` return false when Redis is unreachable, so a partitioned node
  stops driving timers, claiming tables, and (with routing on) processing actions until Redis recovers.
- `acquire`/`isOwner` now distinguish single-node mode (ownership disabled → always owns) from cluster mode
  with Redis unreachable (fail-open vs fail-closed), instead of collapsing both to "owns everything".
- `TableOwnershipServiceTest` adds fail-closed coverage (Redis-missing and Redis-error refuse ownership;
  single-node mode is unaffected). Default fail-open behaviour is unchanged.

### 🏗️ Architecture — Engine migration Phase 5 (failover takeover)
- Automatic takeover of tables orphaned by a dead owner so a game no longer hangs waiting on a player the
  dead node was meant to time out (previously it recovered only lazily, on the next action for that table).
- Each node records active game tables in a Redis set (`truholdem:cluster:tables`, added by
  `TableOwnershipService.trackActiveTable` when a turn timer is armed, removed on game end);
  `ClusterFailoverService` scans the set ~twice per lease TTL and, for any table whose lease has expired
  (no current owner), re-acquires it and resumes the stalled turn timer. Finished/missing games are pruned.
- Gated by `app.cluster.takeover-enabled` (default off, requires `ownership-enabled`); inert otherwise.
- Takeover resumes whatever timer the dead owner was driving: the in-progress turn timer **and** the
  between-hands transition. `GameHandLifecycleService.resumePendingTransition` re-schedules a table
  orphaned in `HAND_COMPLETED`/`RESULT_DELAY` (so a game does not stall forever between hands either);
  each branch is state-guarded. Prune is keyed on the game being absent from shared state — not on
  `isFinished()`, which is also true between hands — so an active between-hands table is no longer
  mistakenly dropped.
- `MultiNodeClusterIT` adds a kill-node test: node-A is shut down and its lease expired, then node-B takes
  over the orphaned table and resumes its timer. `ClusterFailoverServiceTest` covers claim/resume,
  still-owned-skip, lost-race, and missing-game-prune; `GameHandLifecycleServiceTest` covers
  `resumePendingTransition` routing per state. (The narrow transient `NEXT_HAND` crash window is not
  resumed; it remains a documented follow-up.)
- Doc correction: WebSocket-origin actions were already routed cross-node (the WS handler calls the same
  `playerAct`); the earlier "WS-origin forwarding remaining" note was overly cautious.

### 🏗️ Architecture — Engine migration Phase 5 (cross-node command routing)
- Cross-node action routing so same-table multiplayer is correct across a cluster: `PokerGameService.playerAct`
  routes at the service layer — if this node can't acquire the table's lease it resolves the owner from a
  Redis node registry (`truholdem:cluster:node:{instanceId}` → peer base URL, written on startup and refreshed
  in the ownership heartbeat) and forwards the action to the owner over HTTP.
- New `ClusterActionForwarder` POSTs to the owner's `/internal/cluster/game/{id}/action` endpoint
  (`ClusterInternalController`), authenticated by a constant-time shared-secret header (`X-Cluster-Secret`).
  The owner applies the action on its own single-writer queue and persists to the authoritative shared
  hot-state; the originating node reloads and returns it. The `commandId` is carried through (exactly-once
  preserved), a non-routing `playerActLocal` path prevents forward loops, and one re-claim covers an owner
  that died mid-flight.
- Gated by `app.cluster.routing-enabled` (default off, requires `ownership-enabled`); routing-off behaviour
  is byte-for-byte unchanged. New config: `app.cluster.node-base-url` (this node's peer-reachable URL,
  must include the servlet context-path) and `app.cluster.shared-secret`.
- `MultiNodeClusterIT` upgraded to boot two web instances and verify an action sent to the non-owner
  node is forwarded over real HTTP to the owner and applied exactly once (owner retains the lease).
  `ClusterActionForwarderTest` covers the secret header, unknown-owner and owner-error paths.

### 🏗️ Architecture — Engine migration Phase 5 foundation (per-table ownership)
- New `TableOwnershipService`: a Redis-lease (`truholdem:owner:{uuid}` → node `instanceId`, atomic Lua
  acquire-if-free-or-mine + heartbeat renewal) giving each table/tournament at most one owner node.
- The turn-timeout, hand-lifecycle and tournament blind-level schedulers now acquire ownership before
  scheduling and re-check on fire, so on a multi-node cluster each timer fires on exactly one node
  (eliminating the per-JVM double-fire). Gated by `app.cluster.ownership-enabled` (default off);
  degrades to single-node behavior when disabled or Redis is unavailable.
- The lease is verified against real Redis (`TableOwnershipRedisIT`, Testcontainers): exclusive
  acquire, release handoff, and TTL-expiry failover.
- Multi-instance harness `MultiNodeClusterIT`: boots two full app instances against one shared
  Postgres + Redis (cluster mode on) and asserts cross-node ownership exclusivity — the base for
  verifying cross-node routing / failover. Cross-node command routing and live kill-node takeover remain.

### 🐛 Fixes
- Remove a duplicate `WebSocketEventListener` (`com.truholdem.listener` vs `com.truholdem.application.listener`):
  both were `@Component @ConditionalOnProperty(app.websocket.cluster.enabled)`, so enabling cluster mode
  crashed startup with a conflicting-bean-definition error. Surfaced by `MultiNodeClusterIT`.

### 🏗️ Architecture — Engine migration Phase 6 (cleanup & enforcement)
- Removed dead code: unused `GameUpdateType` values (`NEW_HAND`/`PLAYER_JOINED`/`PLAYER_LEFT`/
  `PHASE_CHANGE`/`GAME_ENDED`) and the never-called `broadcastPhaseChange`/`broadcastGameEnded` methods.
- `StatisticsController` and `AchievementController` now return DTOs (`PlayerStatisticsResponse`,
  `AchievementResponse`, `PlayerAchievementResponse`) instead of JPA entities; the tournament
  table-hand endpoint returns the sanitized projection. Wire JSON unchanged (contract-tested).
- New ArchUnit rule fails the build if any `@RestController` exposes `com.truholdem.model.*` in a
  return type (reflective generic-type scan), enforcing the controller↔persistence decoupling.

### 🏗️ Architecture — Engine migration Phase 4 (append-only event log + replay)
- New `game_event_log` table (Liquibase changeset 13): a synchronous `GameEventLogListener` persists
  every published domain event (JSON payload, global `seq_no` ordering, stamped `gameId`/`handNumber`).
  The writer runs in its own `REQUIRES_NEW` transaction and is best-effort, so an audit failure never
  blocks or rolls back a game action. Gated by `app.game.event-log-enabled` (default on; aggregate path).
- Replay-from-events read API on `HandHistoryController`: `GET /history/game/{id}/events` and
  `GET /history/game/{id}/hand/{n}/events` return the ordered domain-event stream for audit/replay.
- Reconnect/resume was already provided by the Redis `GameEventStore` layer and is left unchanged.

### 🏗️ Architecture — Engine migration Phase 3 (hand-history read-model)
- `HandHistoryController` now returns a dedicated `HandHistoryResponse` read DTO instead of the raw
  `HandHistory` JPA entity, decoupling the API from persistence. The wire JSON is byte-for-byte
  unchanged (no frontend change), pinned by a new `HandHistoryJsonContractTest`.

### 🏗️ Architecture — Engine migration Phase 3 (event-driven statistics)
- Domain events raised by the `PokerGame` aggregate are now **published to Spring** via
  `DomainEventPublisher` on the aggregate engine path (previously they were collected and dropped).
- `StatisticsEventListener` is implemented (and made synchronous) to derive player statistics from
  `PlayerActed`/`HandCompleted` events; the duplicate imperative `playerStatisticsService` calls were
  removed from the aggregate path. Wins are counted from `HandCompleted` only (no double counting).
- Gated by `app.game.engine=AGGREGATE`; the default legacy path keeps its imperative statistics.

### 🏗️ Architecture — Engine migration Phase 2 (single-writer per table)
- **`TableCommandDispatcher`**: every mutation for a table (`gameId`) is now serialized on a
  per-game command chain running over a shared bounded thread pool (no thread-per-table — scales
  to thousands of tables). Player actions, bot actions, turn timeouts and hand-lifecycle
  transitions all funnel through the same queue, eliminating the action-vs-timeout interleave and
  the `@Version` lost-update race on a single node.
- **`commandId` idempotency**: a per-table bounded TTL cache replays the recorded result/exception
  for a duplicate command, so a double-click or a duplicate WebSocket frame is applied exactly
  once. The id is supplied by the client (`X-Command-Id` header on REST, `commandId` field on the
  WS payload; the Angular `PokerService`/`WebSocketService` reuse the id across retries of the same
  action) and generated server-side when absent.
- **Feature flag** `app.game.single-writer-enabled` (default **off** → legacy lock-free path) gates
  the whole mechanism for fast rollback.

### 🧪 Testing
- `TableCommandDispatcherTest`: serialized no-lost-updates under concurrent load, parallelism across
  tables, exactly-once idempotency, original-type exception propagation, re-entrancy safety.
- `PokerGameService` routing test; frontend Jest specs for `X-Command-Id` send + retry reuse.

## [2.0.0] - 2024-12-16

### ✨ Features
- **Game Engine**: Complete Texas Hold'em implementation with all betting rounds
- **Tournament System**: Multi-table tournaments with Sit & Go and Scheduled formats
- **Bot AI**: Advanced AI with Monte Carlo simulations, opponent modeling, and GTO-inspired play
- **Hand Analysis**: Equity calculator, range builder, and EV analysis tools
- **Real-time WebSocket**: Cluster-ready WebSocket implementation with Redis pub/sub
- **Authentication**: JWT-based auth with refresh tokens and RBAC
- **Statistics**: Comprehensive player statistics and leaderboards
- **Hand History**: Full hand replay with action-by-action analysis

### 🏗️ Architecture
- **Backend**: Spring Boot 3.5 with Java 21, PostgreSQL, Redis
- **Frontend**: Angular 20 with NgRx ComponentStore, lazy loading, route guards
- **DDD**: Domain-Driven Design with aggregates, value objects, and domain events
- **Observability**: OpenTelemetry tracing, Prometheus metrics, Grafana dashboards

### 🧪 Testing
- 190+ unit and integration tests
- Cypress E2E tests with accessibility checks
- Architecture tests with ArchUnit
- 60%+ code coverage

### 🚀 DevOps
- Docker Compose for local development
- CI/CD pipeline with GitHub Actions
- Automatic semantic versioning and releases
- Security scanning with Trivy
- Dependabot for dependency updates

### 📚 Documentation
- Comprehensive README with setup instructions
- API documentation with Swagger/OpenAPI
- Architecture decision records
- Contributing guidelines

---
*Generated by [git-cliff](https://github.com/orhun/git-cliff)*
