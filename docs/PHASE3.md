# Phase 3 — Async Persist, Table Sharding, Load Smoke Test

## Goal

Further reduce latency on hand milestones and scale tournament WebSocket fan-out for large-field MTT.

## Components

| Component | Role |
|-----------|------|
| `AsyncGamePersistService` | `@Async("gamePersistExecutor")` PostgreSQL writes after Redis snapshot |
| `GameStateService.persistFull` | Queues async PG when `async-persist-enabled` and game id exists |
| `GameStateService.persistFullSync` | Always synchronous (game creation) |
| `TournamentTableShardService` | Shard index + table/shard WebSocket destinations |
| `GameNotificationService` | Fans out to `/topic/game/{id}`, table, and shard topics |
| `TournamentWebSocketController` | `TABLE_CREATED` also sent to per-table and shard topics |

## Configuration

```properties
app.game.async-persist-enabled=true

app.tournament.shard-count=16
app.tournament.table-topics-enabled=true
```

Unit tests: `async-persist-enabled=false`.

## WebSocket topics

| Topic | Use |
|-------|-----|
| `/topic/tournament/{tournamentId}` | Tournament-wide events |
| `/topic/tournament/{tournamentId}/table/{n}` | Single table |
| `/topic/tournament/{tournamentId}/shard/{s}` | Shard bucket (`tableNumber % shardCount`) |
| `/topic/game/{gameId}` | Cash / per-table game state (unchanged) |

## Thread pool

`gamePersistExecutor`: core 4, max 16, queue 512.

## Tests

- `GameStateServiceTest` — async persist path
- `TournamentTableShardServiceTest` — shard/topic mapping
- `GameStateConcurrencyTest` — 32×25 concurrent `afterPlayerAction` smoke test
