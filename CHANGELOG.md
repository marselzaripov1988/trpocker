# Changelog

All notable changes to TruHoldem will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
