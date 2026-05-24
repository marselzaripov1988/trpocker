# Phase 2 — Redis Hot Game State

## Goal

Reduce PostgreSQL load during active play by keeping authoritative in-hand state in Redis and deferring durable writes to hand milestones.

## Components

| Component | Role |
|-----------|------|
| `GameStateService` | Routes load/save between Redis and PostgreSQL |
| `RedisGameStateStore` | JSON snapshot at `truholdem:game:state:{id}` (24h TTL) |
| `PlayerStatisticsService` | Optional buffer for per-action stats until hand end |

## Configuration (`application.properties`)

```properties
app.game.hot-state-enabled=true
app.game.persist-on-hand-end-only=true
app.game.buffer-statistics-on-actions=true
app.game.bot-monte-carlo-iterations=500
```

Unit tests disable hot state in `application-test.properties`.

## Write paths

- **Player action (hand in progress):** Redis only when `persist-on-hand-end-only=true`
- **Hand finished / new hand / create game:** `persistFull` → PostgreSQL + Redis refresh
- **Statistics:** `recordAction` / `recordAllIn` buffered; `flushBufferedActionsForGame` on hand end

## Requirements

- Redis (`spring.data.redis`) must be available when `hot-state-enabled=true`
- Cluster WebSocket (`app.websocket.cluster.enabled`) is independent of game state Redis

## Next

See [PHASE3.md](PHASE3.md) for async persist, table sharding, and concurrency smoke tests.
