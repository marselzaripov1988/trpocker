# 🃏 TruHoldem

[![CI/CD](https://github.com/APorkolab/TruHoldem/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/APorkolab/TruHoldem/actions/workflows/ci-cd.yml)
[![codecov](https://codecov.io/gh/APorkolab/TruHoldem/branch/main/graph/badge.svg)](https://codecov.io/gh/APorkolab/TruHoldem)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-green.svg)](https://spring.io/projects/spring-boot)
[![Angular](https://img.shields.io/badge/Angular-20-red.svg)](https://angular.io/)

**A production-ready Texas Hold'em poker platform with advanced Bot AI, multi-table tournaments, real-time WebSocket gameplay, and comprehensive observability.**

> 🎯 **Portfolio Project** — Demonstrates senior-level full-stack development with enterprise-grade architecture patterns, comprehensive test coverage (2,500+ tests), and production-ready DevOps infrastructure.

<p align="center">
  <img src="frontend/src/assets/Baccaratio.png" alt="TruHoldem Logo" width="200"/>
</p>

---

## ✨ Features

### Core Gameplay
- **Real-time multiplayer** — WebSocket-based gameplay with instant updates
- **Complete Texas Hold'em rules** — Pre-flop, flop, turn, river betting rounds
- **Side pot management** — Automatic handling of all-in situations with multiple side pots
- **Hand evaluation** — Full poker hand ranking from high card to royal flush
- **Official poker rules** — Dead button handling, showdown order (last aggressor first), missed blinds tracking
- **Short all-in support** — Players can go all-in even if they can't afford minimum raise

### Tournament System
- **Multi-table tournaments** — Dynamic table balancing and player redistribution
- **Sit & Go / Scheduled** — Multiple tournament formats with configurable parameters
- **Blind structures** — Standard, Turbo, and Deep stack configurations
- **Rebuy/Add-on** — Configurable rebuy periods and limits
- **Prize distribution** — Automatic payout calculation with customizable structures

### Advanced Bot AI
- **Monte Carlo simulation** — 500-iteration equity calculations for decision making
- **Position awareness** — Strategic adjustments based on table position
- **Multiple personalities** — Tight-Aggressive, Loose-Aggressive, Tight-Passive, Loose-Passive
- **Opponent modeling** — Tracks and adapts to opponent betting patterns
- **Pot odds & implied odds** — Mathematical decision framework
- **All-in decision making** — Smart call/fold logic when facing all-in bets based on hand strength and pot odds

### Analytics & Statistics
- **Equity calculator** — Real-time hand vs. hand equity analysis
- **Hand history** — Complete game replay with action-by-action breakdown
- **Player statistics** — VPIP, PFR, aggression factor, win rates
- **Leaderboards** — Global and tournament-specific rankings

### Enterprise Features
- **JWT authentication** — Secure token-based auth with refresh tokens
- **WebSocket clustering** — Redis-backed horizontal scaling support
- **Rate limiting** — Configurable request throttling
- **Distributed tracing** — OpenTelemetry integration with Jaeger
- **Metrics & monitoring** — Prometheus metrics with Grafana dashboards
- **API versioning** — Clean REST API with OpenAPI documentation

---

## 🛠 Tech Stack

### Backend
| Technology | Version | Purpose |
|------------|---------|---------|
| Java | 21 | Language (LTS with virtual threads) |
| Spring Boot | 3.5 | Application framework |
| Spring Security | 6.x | Authentication & authorization |
| Spring WebSocket | STOMP | Real-time communication |
| Spring Data JPA | Hibernate | Data persistence |
| PostgreSQL | 16 | Primary database |
| Redis | 7 | Caching & WebSocket sessions |
| Liquibase | 4.x | Database migrations |
| OpenTelemetry | 1.36 | Distributed tracing |
| Micrometer | Prometheus | Metrics collection |

### Frontend
| Technology | Version | Purpose |
|------------|---------|---------|
| Angular | 20 | SPA framework |
| NgRx ComponentStore | 20 | Reactive state management |
| RxJS | 7.8 | Reactive programming |
| Bootstrap | 5.3 | UI components |
| Jest | 30 | Unit testing |
| Cypress | 13 | E2E testing |
| axe-core | 4.11 | Accessibility testing |

### DevOps
| Technology | Purpose |
|------------|---------|
| Docker & Docker Compose | Containerization |
| GitHub Actions | CI/CD pipeline |
| Nginx | Reverse proxy & static serving |
| Prometheus | Metrics aggregation |
| Grafana | Monitoring dashboards |
| Jaeger | Distributed tracing |
| OpenTelemetry Collector | Telemetry pipeline |

---

## 🚀 Quick Start

### Prerequisites
- Docker & Docker Compose
- Java 21+ (for local development)
- Node.js 20-22 (for local development, Node.js 24+ may have webpack compatibility issues)
- PostgreSQL 16 (or use Docker)
- Redis 7 (optional, for WebSocket clustering)

### Docker (Recommended)

```bash
# Clone the repository
git clone https://github.com/APorkolab/TruHoldem.git
cd TruHoldem

# Start all services
docker-compose up -d

# Access the application
# Frontend:     http://localhost:4200
# API:          http://localhost:8080/api
# Swagger UI:   http://localhost:8080/api/swagger-ui.html
# Grafana:      http://localhost:3000 (admin/admin)
# Jaeger:       http://localhost:16686
# Prometheus:   http://localhost:9090
```

### Local Development

```bash
# Recommended: Use the dev-start script (auto-finds available port)
./scripts/dev-start.sh

# Or start manually:

# Backend (Terminal 1)
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Frontend (Terminal 2)
cd frontend
npm install
npm run dev
```

#### Development Script Options

```bash
# Start both backend and frontend
./scripts/dev-start.sh

# Start frontend only (if backend is already running)
./scripts/dev-start.sh --frontend-only
```

#### Clearing Cache (if webpack errors occur)

```bash
cd frontend
rm -rf node_modules/.cache .angular
npm cache clean --force
npm run dev
```

---

## 📁 Project Structure

```
TruHoldem/
├── backend/                    # Spring Boot application
│   ├── src/main/java/com/truholdem/
│   │   ├── config/            # Configuration classes
│   │   ├── controller/        # REST & WebSocket controllers
│   │   ├── domain/            # DDD aggregates, events, value objects
│   │   ├── dto/               # Data transfer objects
│   │   ├── exception/         # Custom exceptions & handlers
│   │   ├── model/             # JPA entities
│   │   ├── observability/     # Metrics, tracing, logging
│   │   ├── repository/        # Data access layer
│   │   ├── security/          # JWT & authentication
│   │   ├── service/           # Business logic
│   │   └── websocket/         # WebSocket infrastructure
│   └── src/test/              # Test suites (1,000+ tests)
│
├── frontend/                   # Angular application
│   ├── src/app/
│   │   ├── analysis/          # Equity calculator, range builder
│   │   ├── auth/              # Login/register components
│   │   ├── game-table/        # Main game interface
│   │   ├── guards/            # Route protection
│   │   ├── hand-replay/       # History playback
│   │   ├── services/          # API & state services
│   │   ├── store/             # NgRx ComponentStore
│   │   └── tournament/        # Tournament components
│   └── cypress/               # E2E tests (190+ tests)
│
├── docker/                    # Docker configurations
│   ├── grafana/              # Dashboard definitions
│   └── prometheus/           # Alert rules
│
├── monitoring/               # Observability configs
└── docs/                     # Documentation
```

---

## 🏗 Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Client Layer                            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │   Angular   │  │   Mobile    │  │   Third-party Clients   │  │
│  │     SPA     │  │   (Future)  │  │      (API consumers)    │  │
│  └──────┬──────┘  └──────┬──────┘  └───────────┬─────────────┘  │
└─────────┼────────────────┼─────────────────────┼────────────────┘
          │ HTTP/WS        │                     │ REST API
          ▼                ▼                     ▼
┌─────────────────────────────────────────────────────────────────┐
│                        API Gateway (Nginx)                      │
│         Load Balancing │ SSL Termination │ Rate Limiting        │
└─────────────────────────────┬───────────────────────────────────┘
                              │
┌─────────────────────────────┼───────────────────────────────────┐
│                    Application Layer                            │
│  ┌──────────────────────────┴────────────────────────────────┐  │
│  │                    Spring Boot Backend                    │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐│  │
│  │  │ REST APIs   │  │  WebSocket  │  │  Background Jobs    ││  │
│  │  │ (Games,     │  │  (STOMP)    │  │  (Tournaments,      ││  │
│  │  │ Tournaments)│  │  Real-time  │  │   Blind increases)  ││  │
│  │  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘│  │
│  │         │                │                     │          │  │
│  │  ┌──────┴────────────────┴─────────────────────┴────────┐ │  │
│  │  │              Service Layer (Business Logic)          │ │  │
│  │  │  PokerGameService │ TournamentService │ BotAIService │ │  │
│  │  └───────────────────────────┬──────────────────────────┘ │  │
│  │                              │                            │  │
│  │  ┌───────────────────────────┴───────────────────────────┐│  │
│  │  │           Domain Layer (DDD Patterns)                 ││  │
│  │  │  Aggregates │ Value Objects │ Domain Events           ││  │
│  │  └───────────────────────────────────────────────────────┘│  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────┬───────────────────────────────────┘
                              │
┌─────────────────────────────┼───────────────────────────────────┐
│                      Data Layer                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌───────────────────────────┐│
│  │ PostgreSQL  │  │    Redis    │  │     Message Queues        ││
│  │  (Primary   │  │  (Sessions, │  │  (Domain Events via       ││
│  │   Storage)  │  │   Caching)  │  │   Spring Events)          ││
│  └─────────────┘  └─────────────┘  └───────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────┼───────────────────────────────────┐
│                   Observability Layer                           │
│  ┌─────────────┐  ┌─────────────┐  ┌───────────────────────────┐│
│  │ Prometheus  │  │   Jaeger    │  │    Grafana Dashboards     ││
│  │  (Metrics)  │  │  (Traces)   │  │    (Visualization)        ││
│  └─────────────┘  └─────────────┘  └───────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

---

## 🧪 Testing

### Test Coverage Summary

| Layer | Tests | Coverage |
|-------|-------|----------|
| Backend Unit/Integration | 1,064 | 85%+ |
| Frontend Unit (Jest) | 1,255 | 80%+ |
| E2E (Cypress) | 194 | Critical paths |
| **Total** | **2,513** | — |

### Running Tests

```bash
# Backend tests
cd backend
./mvnw verify

# Frontend unit tests
cd frontend
npm run test:ci

# Frontend E2E tests
npm run e2e:ci

# All tests
npm run test:all
```

### Test Categories
- **Unit tests** — Service logic, domain objects, utilities
- **Integration tests** — Repository, controller, WebSocket
- **Architecture tests** — Package dependency validation (ArchUnit)
- **E2E tests** — Complete user flows, accessibility (axe-core)

---

## 🤖 Bot AI System

The bot AI uses a sophisticated decision-making framework:

```
┌────────────────────────────────────────────────────────────────┐
│                    Bot Decision Pipeline                       │
├────────────────────────────────────────────────────────────────┤
│  1. Hand Strength Calculation                                  │
│     └─ Monte Carlo: 500 iterations for equity estimation       │
│                                                                │
│  2. Position Analysis                                          │
│     └─ Early/Middle/Late/Button position scoring               │
│                                                                │
│  3. Pot Odds Calculation                                       │
│     └─ pot_odds = to_call / (pot + to_call)                    │
│                                                                │
│  4. Personality Adjustment                                     │
│     ├─ Tight-Aggressive  (TAG): Premium hands, big bets        │
│     ├─ Loose-Aggressive  (LAG): Wide range, pressure           │
│     ├─ Tight-Passive     (TP):  Premium hands, calls           │
│     └─ Loose-Passive     (LP):  Wide range, passive            │
│                                                                │
│  5. Action Selection                                           │
│     └─ Pre-flop strategy │ Post-flop strategy │ Bluff logic    │
└────────────────────────────────────────────────────────────────┘
```

📖 See [docs/BOT_AI.md](docs/BOT_AI.md) for detailed documentation.

---

## 📚 Documentation

| Document | Description |
|----------|-------------|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | System architecture & design decisions |
| [BOT_AI.md](docs/BOT_AI.md) | Bot AI strategy & algorithms |
| [TOURNAMENTS.md](docs/TOURNAMENTS.md) | Tournament system documentation |
| [ANALYSIS.md](docs/ANALYSIS.md) | Hand analysis & equity calculator |
| [DEPLOYMENT.md](docs/DEPLOYMENT.md) | Production deployment guide |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Contribution guidelines |

---

## 🔌 API Documentation

Interactive API documentation is available via Swagger UI:

```
http://localhost:8080/api/swagger-ui.html
```

### Key Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v2/games` | Create new game |
| `POST` | `/api/v2/games/{id}/join` | Join a game |
| `POST` | `/api/v2/games/{id}/action` | Perform game action |
| `GET` | `/api/v2/tournaments` | List tournaments |
| `POST` | `/api/v2/tournaments` | Create tournament |
| `POST` | `/api/v2/analysis/equity` | Calculate equity |
| `GET` | `/api/v2/statistics/leaderboard` | Get leaderboard |

---

## 🚢 Deployment

### Docker Compose (Development/Staging)
```bash
docker-compose up -d
```

### Production Checklist
- [ ] Configure external PostgreSQL/Redis
- [ ] Set secure JWT secrets
- [ ] Configure SSL certificates
- [ ] Set up proper CORS origins
- [ ] Enable rate limiting
- [ ] Configure log aggregation
- [ ] Set up alerting rules

📖 See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for detailed guide.

---

## 📊 Monitoring

### Pre-configured Grafana Dashboards
- **Game Metrics** — Active games, actions/second, hand completion times
- **Tournament Metrics** — Active tournaments, player counts, prize pools
- **System Health** — JVM metrics, HTTP latency, error rates
- **WebSocket Cluster** — Connection counts, message throughput

### Prometheus Alerts
- High error rate (>5% over 5 minutes)
- Slow response times (>1s p95 latency)
- Database connection pool exhaustion
- Memory pressure warnings

---

## 🔍 Code Review Findings (May 2026)

A full read-only review of the backend (Spring Boot) and frontend (Angular) surfaced the
following actionable items, ordered by impact. File references use `path:line`.

### Critical — security & game integrity

| Area | Finding | Location |
|------|---------|----------|
| Card leakage | ✅ **Fixed.** Deck is `@JsonIgnore` and responses go through a viewer-aware `HoleCardSanitizer` (see Engine Migration status). | `model/Game.java`, `service/HoleCardSanitizer.java` |
| Auth bypass | ✅ **Fixed.** `/poker/**` now requires authentication and `LegacyPokerController` validates seat/bot ownership via `GameAuthorizationService` on every action (the `/start` human seat is bound to the caller's user id). | `config/SecurityConfig.java`, `controller/LegacyPokerController.java` |
| Weak JWT default | ✅ **Fixed.** The `prod` profile sets `app.jwt.secret=${JWT_SECRET}` with no fallback, so production fails fast at startup if `JWT_SECRET` is unset. The insecure default remains only for dev/test. | `application-prod.properties` |
| Error handling | ✅ **Fixed.** Prod `GlobalExceptionHandler` now maps `AccessDeniedException`→403 and `AuthenticationException`→401. (No `src/test` duplicate advice exists anymore.) | `exception/GlobalExceptionHandler.java` |
| WebSocket broken | ✅ **Fixed.** `sockjs-client` / `@stomp/stompjs` are declared in `package.json` and imported as real modules (no more undefined globals); `esModuleInterop` unified across build/test tsconfig. | `services/websocket.service.ts`, `frontend/package.json` |

### High — architecture & consistency

| Area | Finding | Location |
|------|---------|----------|
| Dual game models | Rich `domain.aggregate.PokerGame` is never used on the live path; all logic lives in `PokerGameService` over the anemic `model.Game`. | `domain/aggregate/PokerGame.java`, `service/PokerGameService.java` |
| Dead events | `HandCompleted` is never published live; `StatisticsEventListener` is an unreachable stub. | `service/PokerGameService.java`, `application/listener/StatisticsEventListener.java` |
| Schedulers | Turn-timeout / hand-lifecycle / blind-level schedulers keep `ScheduledFuture` in per-JVM maps — not cluster-safe; tournament resume can leak timers. | `service/GameTurnTimeoutService.java`, `service/GameHandLifecycleService.java`, `service/TournamentStartService.java` |
| Optimistic locking | `@Version` exists but no conflict handling; hot-state + async persist can silently lose updates. | `model/Game.java:49-51`, `service/AsyncGamePersistService.java` |
| CORS | Origins configured in three contradictory places (hardcoded `SecurityConfig`, `app.cors.*`, WebMvc `CorsConfiguration`). | `config/SecurityConfig.java:147-156`, `application.properties:97`, `config/CorsConfiguration.java` |
| Config conflicts | Duplicate keys `app.game.max-players`(6) vs `app.game.maxPlayers`(8); `validatePlayerCount` ignores config (hardcoded 2–10). | `application.properties:106-122`, `service/PokerGameService.java:385-388` |
| Frontend timers/polling | `TournamentStore.startPollingFallback` stacks RxJS timers; turn-timer + bot orchestration duplicated across both table components. | `store/tournament.store.ts:567-574`, `game-table.component.ts:245-258`, `tournament-table.component.ts:876-889` |
| Fragile global CSS | `styles.scss` targets build-generated `[_ngcontent-ng-cXXXX]` hashes with `!important`; rules silently break on each build. | `styles.scss:40-49` |

### Medium — cleanup

- Controllers return JPA entities instead of DTOs (couples API to persistence, worsens card leakage).
- `@Cacheable getGame` caches full mutable state (incl. deck) for 5 minutes.
- Dead code: `GameUpdateType.NEW_HAND/GAME_ENDED/PLAYER_JOINED/PLAYER_LEFT`, `broadcastPhaseChange`/`broadcastGameEnded`, unused `RedisGameEventBroadcaster` on the live path.
- `model/game.ts` `handLifecycleState` is defined but unused; "finished" state duplicated as `isFinished || phase === 'SHOWDOWN'`.
- Dev (`ddl-auto=update`, Liquibase off) vs prod (`validate`, Liquibase on) schema drift risk.
- `console.log` inside hot store selectors; legacy `PokerService`/`GameStateService` parallel to `GameStore`.

### Suggested fix order

1. ✅ Stop card/deck leakage (player-scoped DTOs / `@JsonView`).
2. ✅ Lock down or remove the unauthenticated legacy game API; fail startup without `JWT_SECRET` in prod.
3. ✅ Merge auth handlers into the prod exception handler; delete the test-side duplicate.
4. ✅ Bundle SockJS/STOMP so WebSocket works at runtime.
5. Decide on a single game model and remove the dead DDD/event path.
6. Frontend: dedupe turn-timer/bot logic, fix polling stacking, move `_ngcontent` CSS into component styles.

> **Note (June 2026):** Items 1–4 are done. A residual limitation remains on the legacy
> `/poker/**` API: `LegacyPokerController` still tracks a single shared `currentGameId` field, so
> it supports one game at a time per backend instance. Per-session isolation (or migrating the
> frontend onto the authenticated `/game` API, item 5) is the recommended next step.

---

## 🧭 Poker Engine Migration Plan (Production DDD)

The current engine has two parallel models: a rich `domain.aggregate.PokerGame` (added first,
never wired to live traffic) and an anemic `model.Game` driven by a large `PokerGameService`.
For a real high-load product the target is a genuine domain core with event sourcing and CQRS,
introduced **incrementally** so the live REST/WebSocket path keeps working after every phase.
Each risky step is guarded by a feature flag for fast rollback.

> **Status (current):** Phases 0–6 are done (Phase 5 functionally complete; only edge-case hardening
> remains). Phase 2 added per-table
> single-writer serialization with `commandId` idempotency; Phase 3 wired domain events to statistics and
> gave reads dedicated projections; Phase 4 added the append-only Postgres `game_event_log` (audit +
> replay-from-events); Phase 6 removed dead code and added ArchUnit enforcement; Phase 5 landed the
> clustering foundation — Redis-lease per-table ownership so timers fire on one node only
> (`app.cluster.ownership-enabled`), **cross-node command routing** (a node that receives an action for a
> table it doesn't own forwards it over HTTP to the owning node, `app.cluster.routing-enabled`), and
> **failover takeover** (a surviving node re-acquires a dead owner's table and resumes its stalled timer,
> `app.cluster.takeover-enabled`, resuming both the in-progress turn timer and the between-hands
> transition), an optional **fail-closed** mode (`app.cluster.fail-closed`) that refuses ownership when
> Redis is unreachable, and **fencing tokens** (`app.cluster.fencing-enabled`) that fence a stale former
> owner's hot-state write out at the store — all verified on a two-node Testcontainers harness. Phase 5 is
> functionally complete (see FUTURE_IMPROVEMENTS for the remaining edge-case hardening).
> Card leakage is closed: the deck is never serialized, and REST/WS responses run through
> a viewer-aware `HoleCardSanitizer` that masks opponents' hole cards until showdown
> (own seats always revealed; folded hands stay hidden). WS broadcasts mask all hands and
> the frontend preserves the local player's own hand across masked updates.

### Phase 0 — Safety net ✅ done
- Golden black-box scenario tests on the existing `PokerGameService`: all-in, side pot, short
  all-in, fold-to-showdown, timeout, next-hand transition, showdown order, dead button, missed blinds.
- Snapshot tests for REST/WS JSON contracts so the frontend can't silently break.
- Remove the duplicate exception handler (`exception.GlobalExceptionHandler` vs the test-side copy)
  by merging auth handlers into the production advice.
- **Exit:** a stable regression suite that passes on the current code.
- **TODO (deferred, pre-existing):** three full-context `@SpringBootTest` classes
  (`ApiVersionConfigTest`, `GameControllerIntegrationTest`, `FullGameIntegrationTest`) fail to load
  their context because the datasource resolves to the PostgreSQL URL while the H2 driver is active
  (they are written for a Testcontainers-managed Postgres that is not wired up). This is unrelated to
  the engine migration and pre-dates Phase 0 (verified on a clean baseline); fix the Testcontainers
  wiring so `mvnw verify` is fully green.

### Phase 1 — Clean domain core behind a facade ✅ done
- Flesh out `domain.aggregate.PokerGame` with commands and protected invariants; `PokerGameService`
  becomes a thin orchestration facade. `model.Game` is demoted to a JPA snapshot (aggregate ⇄ entity mapping).
- Move the golden scenarios down to fast, Spring-free unit tests on the aggregate.
- **Exit:** hand logic is testable in isolation; `PokerGameService` shrinks to orchestration.

### Phase 2 — Commands, idempotency, single-writer per table ✅ done (single node)
- ✅ `TableCommandDispatcher` serializes every mutation per `tableId` on a per-game chain over a
  shared bounded pool (no thread-per-table; scales to thousands of tables). Player actions, bot
  actions, turn timeouts and hand-lifecycle transitions all funnel through the same queue, so the
  action-vs-timeout interleave and the `@Version` lost-update race cannot occur on one node.
- ✅ `commandId` idempotency: a per-table bounded TTL cache replays the recorded result/exception
  for a duplicate id, so a double-click or a duplicate WebSocket frame applies exactly once. The id
  flows from the client (`X-Command-Id` header on REST, `commandId` on the WS payload; the Angular
  services reuse the id across retries of the same action) and is server-generated when absent.
- Gated by `app.game.single-writer-enabled` (default **off** → legacy lock-free path) for rollback.
- **Exit:** ✅ no races on a single node; `TableCommandDispatcherTest` proves serialized
  no-lost-updates under concurrent load + exactly-once idempotency.
- **Follow-up (Phase 3+):** async-Postgres writes for one game may still land out of order on the
  shared persist pool; harmless today because Redis hot-state is authoritative, but worth ordering
  per table when the event log lands.

### Phase 3 — Domain events & read projections (CQRS) ✅ done
- ✅ Aggregate emits `PlayerActed`, `PotAwarded`, `HandCompleted`, `PhaseChanged`, `GameStarted`.
- ✅ **Statistics flow through events** on the aggregate path: `PokerGameService` publishes the
  aggregate's events via `DomainEventPublisher`, and `StatisticsEventListener` derives stats from
  `PlayerActed`/`HandCompleted` (replacing the previous imperative `playerStatisticsService` calls,
  which were duplicated and the listener was an unreachable stub). The legacy path keeps its
  imperative stats, so this is gated by `app.game.engine=AGGREGATE`.
- ✅ Sanitized read projection: REST/WS responses go through a viewer-aware `HoleCardSanitizer`.
  The **deck is never serialized to clients** (`@JsonIgnore` on the REST mapper; the hot-state Redis mapper
  re-exposes it so the authoritative live state stays playable across nodes); **opponents' hole cards** are masked until
  showdown (own seats revealed, folded hands stay hidden). WS broadcasts mask all hands and the
  Angular store restores the local player's own hand across masked updates.
- ✅ Dedicated history read-model: `HandHistoryController` returns a `HandHistoryResponse` DTO instead of
  the raw `HandHistory` JPA entity (decoupling the API from persistence), serializing to an identical
  JSON shape pinned by `HandHistoryJsonContractTest`. The live/spectator view is the viewer-aware
  `HoleCardSanitizer` projection (a spectator with no seat sees all hands masked).
- **Exit:** ✅ reads use dedicated projections (sanitized live view + `HandHistoryResponse`); ✅ statistics
  flow through events (aggregate path).

### Phase 4 — Event log / snapshots & audit ✅ done (single node)
- ✅ Append-only `game_event_log` table in Postgres: a synchronous `GameEventLogListener` persists every
  published domain event (JSON payload, global `seq_no` ordering, stamped `gameId`/`handNumber`). The
  `Game` row (+Redis hot state) is the snapshot half; the log is the event tail. The writer is
  best-effort (`REQUIRES_NEW`, errors logged not propagated) so audit never blocks gameplay; gated by
  `app.game.event-log-enabled` (default on, aggregate engine path only).
- ✅ Replay-from-events read API: `GET /history/game/{id}/events` and
  `GET /history/game/{id}/hand/{n}/events` return the ordered event stream
  (`GameStarted → PlayerActed… → PotAwarded → HandCompleted`). Hole cards are not present (never
  emitted as events) — hole-card replay stays on the `HandHistory`/`ReplayData` path.
- ✅ Reconnect/resume was already satisfied before this slice by the Redis `websocket/GameEventStore`
  (per-game sequence) + `ReconnectionController /app/reconnect`; left unchanged (re-basing it on Postgres
  is deferred to clustering, Phase 5).
- **Exit:** ✅ any hand can be replayed from events; ✅ reconnect doesn't break the session.

### Phase 5 — Clustering & scale ✅ done (edge-case hardening pending)
- ✅ Per-table ownership: `TableOwnershipService` holds a Redis lease (`truholdem:owner:{uuid}` →
  `instanceId`, atomic Lua acquire-if-free-or-mine + a heartbeat that renews held leases). The turn-timeout,
  hand-lifecycle and tournament blind-level schedulers now **acquire ownership before scheduling and
  re-check at fire**, so on a cluster each timer fires on exactly one node (no double-fire). Gated by
  `app.cluster.ownership-enabled` (default off → single-node behavior); degrades to single-node if Redis
  is unavailable.
- ✅ Hot state already shared (Redis `RedisGameStateStore` + Postgres `game_event_log`), so a node failure
  loses no state, and a dead owner's lease expires so another node re-acquires on the next action.
- ✅ The lease semantics are verified against **real Redis** (`TableOwnershipRedisIT`, Testcontainers):
  two `TableOwnershipService` nodes contend for one table — exclusive acquire, release handoff, and
  TTL-expiry failover all pass.
- ✅ **Cross-node command routing** (`app.cluster.routing-enabled`, default off, requires ownership):
  `PokerGameService.playerAct` routes at the service layer — if this node can't acquire the table's lease,
  it resolves the owner from a Redis node registry (`truholdem:cluster:node:{instanceId}` → base URL) and
  `ClusterActionForwarder` POSTs the action to the owner's secured `/internal/cluster/game/{id}/action`
  endpoint (constant-time shared-secret header). The owner applies it on its own single-writer queue and
  persists to the authoritative shared hot-state; the originating node reloads and returns it. The action
  carries its `commandId` (exactly-once preserved), a non-routing `playerActLocal` path prevents forward
  loops, and one re-claim covers an owner that died mid-flight. So **every action for a table is serialized
  on exactly one node** → correct concurrent same-table multiplayer across the cluster.
  Both REST (`PokerGameController`/`LegacyPokerController`) and **WebSocket** (`GameWebSocketController`)
  actions go through `playerAct`, so both are routed; cross-node delivery of the resulting update to
  clients on other nodes rides the existing ws-cluster fan-out (`RedisGameEventBroadcaster`).
- ✅ **Failover takeover** (`app.cluster.takeover-enabled`, default off, requires ownership): each node
  records active tables in a Redis set (`truholdem:cluster:tables`, added when a turn timer is armed,
  removed when the game ends) and `ClusterFailoverService` periodically scans it. A table whose owner
  died (lease expired → no current owner) is re-acquired by a surviving node, which then resumes whatever
  timer the dead owner was driving — **both** the in-progress turn timer **and** the between-hands
  transition (`HAND_COMPLETED`/`RESULT_DELAY` → next hand), each state-guarded so exactly the applicable
  one fires. So a game no longer hangs waiting on a player the dead owner was meant to time out, nor stalls
  forever between hands (previously it recovered only lazily, on the next action). Games no longer in
  shared state are pruned from the set; a genuinely-over game is removed by the next-hand path itself.
- ✅ A **multi-instance harness** exists (`MultiNodeClusterIT`): boots two full **web** app instances against
  one shared Postgres + Redis (cluster mode + routing + takeover on) and asserts (a) cross-node ownership
  exclusivity, (b) an action sent to the non-owner node is forwarded over real HTTP to the owner and applied
  once, and (c) **kill-node failover** — after node-A is shut down and its lease expires, node-B takes over
  the orphaned table and resumes its timer. (It already surfaced + fixed a real cluster-mode bug: a duplicate
  `WebSocketEventListener` bean that crashed startup whenever `app.websocket.cluster.enabled=true`.)
- ✅ **Split-brain safety — fail-closed mode** (`app.cluster.fail-closed`, default off): the ownership
  lease normally *fails open* — if a node can't reach Redis it assumes it owns its tables, which keeps a
  single node playable but lets a partitioned node double-own a table in a real cluster. With fail-closed
  on, a node that cannot consult Redis **refuses** ownership (`acquire`/`isOwner` → false), so it stops
  driving timers, claiming tables, and (with routing on) processing actions until Redis is reachable again
  — trading availability for safety against two nodes mutating the same table. No effect in single-node
  mode (ownership disabled still owns everything).
- ✅ **Fencing tokens** (`app.cluster.fencing-enabled`, default off, requires ownership + hot-state): each
  lease acquisition carries a monotonic token (Redis `truholdem:cluster:fence:{id}`, bumped only when
  ownership changes hands, kept on renewal). The authoritative Redis hot-state write is an atomic Lua
  compare-and-set that **rejects** any write whose token is behind the table's current token
  (`StaleOwnershipException`). So a former owner paused by a long GC — during which its lease expired and
  another node took over — cannot wake up and clobber the new owner's state; its stale write is fenced
  out at the store. (Postgres is independently guarded by the `@Version` optimistic lock; Redis is the
  authoritative copy.)
- ✅ **Runnable cluster**: `docker-compose.cluster.yml` boots two nodes behind an nginx load balancer on a
  shared Postgres + Redis with all the above flags on (`docker compose -f docker-compose.cluster.yml up
  --build` → http://localhost:8090). nginx uses `ip_hash` stickiness, proxies the WebSocket upgrade, and
  blocks `/api/internal/**` from clients. See **[docs/cluster.md](docs/cluster.md)** for a guided run +
  failover verification (inspecting leases, the node registry, fencing tokens, and a kill-node takeover).
- 🚧 Remaining: recovery of the narrow transient `NEXT_HAND` crash window (state persisted but the
  synchronous next-hand deal interrupted mid-flight).
- **Exit:** ✅ no timer double-fire across nodes; ✅ lease failover proven against real Redis; ✅ cross-node
  action routing applied exactly once on the owner; ✅ kill-node takeover of an orphaned table's turn timer,
  all verified on the two-node harness.

### Phase 6 — Cleanup & enforcement ✅ done
- ✅ Dead code removed: unused `GameUpdateType` values (`NEW_HAND`/`PLAYER_JOINED`/`PLAYER_LEFT`/
  `PHASE_CHANGE`/`GAME_ENDED`) and the never-called `broadcastPhaseChange`/`broadcastGameEnded`
  methods in `GameNotificationService` + `RedisGameEventBroadcaster`.
- ✅ Controllers no longer return JPA `model.*` entities: `StatisticsController` and
  `AchievementController` now return DTOs (`PlayerStatisticsResponse`, `AchievementResponse`,
  `PlayerAchievementResponse`); the tournament table-hand endpoint returns the sanitized projection.
  All shape-preserving (contract tests assert DTO JSON ≡ entity JSON).
- ✅ ArchUnit enforcement: a reflective rule fails the build if any `@RestController` exposes
  `com.truholdem.model.*` in a (generic) return type; the existing domain-independence rule guards
  orphaned domain classes.

**Value order if constrained:** Phases 0 → 1 → 3 deliver ~80% of the benefit (correctness,
testability, no card leakage, real events). Phases 2, 4, 5 add the high-load / fault-tolerance
properties and can be staged as traffic grows.

---

## 🛣 Roadmap

### Completed
- [x] Official poker rules (dead button, showdown order, missed blinds)
- [x] Short all-in support per official rules
- [x] Advanced bot AI with all-in handling
- [x] Dark theme raise modal
- [x] Per-table single-writer engine + `commandId` idempotency (migration Phase 2, single node)

### Planned
- [ ] Mobile-responsive redesign
- [ ] Multi-currency support
- [ ] Advanced hand range visualization
- [ ] AI-powered hand review
- [ ] Kubernetes deployment manifests
- [ ] Integration with poker training tools
- [ ] Player avatars and customization
- [ ] Chat functionality
- [ ] Hand history export (PokerStars format)

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.


Главное, чего сейчас не хватает в покерном движке:

Таймер хода на backend Сейчас 30 секунд добавлены на frontend. Но надёжнее, чтобы timeout контролировал сервер: если игрок закрыл вкладку, сервер всё равно должен сделать auto-check / auto-fold.

Стабильное завершение раздачи и переход к следующей Уже были признаки зависания на окончании руки. Нужен серверный state machine: HAND_COMPLETED -> RESULT_DELAY -> NEXT_HAND, без зависимости от клиента.

Полноценный spectator/admin view Сейчас админ может открыть стол, но лучше иметь отдельный read-only режим: все места, статус руки, история действий, без кнопок игрока.

История действий на столе Не хватает видимого лога: кто сделал raise/call/fold, размер ставки, улица, pot. Это важно и для игрока, и для debug.

Side pots и all-in визуализация Судя по описанию, логика есть, но на UI нужно явно показывать side pots, all-in eligible pot, кто за какой банк борется.

Правильное раскрытие карт Нужно строго: folded карты никогда не раскрываются; на showdown показываются только карты игроков, дошедших до showdown.

Reconnect/resume Если игрок обновил страницу, он должен вернуться за свой стол и видеть актуальное состояние без поломки websocket/session.

Защита от двойных действий Нужно серверно блокировать повторные клики/дубли websocket/http: один ход игрока = одна валидная action.

Турнирная логика мест/пересадки Для MTT/Pyramid важно надёжно: выбивание, пересадка, финальный стол, закрытие пустых столов, сохранение chip stacks между руками.

Тесты сценариев игры Самые нужные: all-in, side pot, short all-in, fold до showdown, timeout, переход к следующей руке, rebalance, финальный стол.

Если коротко: сейчас больше всего не хватает серверного таймера хода, надёжного lifecycle раздачи, и детального action log на столе.