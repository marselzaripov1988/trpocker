# Phase 4a — Live Game on Tournament Table

## Goal

Connect tournament table UI to a real poker hand: shared player IDs, tournament blinds, REST + WebSocket.

## API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/tournaments/{id}/tables/{tableId}` | Table detail with `players[]` and `currentGameId` |
| `POST` | `/api/v1/tournaments/{id}/tables/{tableId}/hand` | Get active game or start new hand → `Game` JSON |
| `GET` | `/api/v1/poker/game/{gameId}` | Poll game state |
| `POST` | `/api/v1/poker/game/{gameId}/player/{playerId}/action` | Player action |

`TableSummary` in tournament detail now includes `currentGameId`.

## Backend

- `TournamentTableGameService` — builds `PlayerInfo` with registration `playerId`, tournament blinds
- `PokerGameService.createNewGame(players, smallBlind, bigBlind)` — optional blinds + stable player UUIDs

## Frontend

- `TournamentStore.ensureTableHand` — POST hand when seated at table
- `GameStore.connectToTournamentGame` — load game + subscribe `/topic/game/{id}`
- `GameStore` tournament mode — v1 REST for actions and bot turns
- `TournamentTableComponent` — wires store effects on `:id/play` route

## Tests

- `TournamentControllerIT` — `POST .../hand`
- `TournamentIT.TableHandPlayTests` — start hand on running tournament
- `TournamentChipSyncService` + `TournamentChipSyncServiceTest` — chip sync after finished hand (wire in 4b)
- `tournament.store.spec` — `myTable`, `ensureTableHand`
- `game.store.spec` — `connectToTournamentGame`, v1 `playerAction`

## Phase 4b — Chip sync after hand

- `TournamentChipSyncService.syncAfterHand` copies stacks from a finished `Game` into `TournamentRegistration`
- `PokerGameService.persistAfterAction` invokes sync when the game belongs to a tournament table (`TournamentTableShardService.findTableForGame`)

## Phase 4c — Blind timer

- `TournamentTimingService` — level duration from blind structure or `app.tournament.level-duration-seconds` (IT uses 5s)
- `TournamentDetailResponse` — `levelEndTimeEpochMillis`, `levelDurationSeconds`
- WS `BLIND_LEVEL_INCREASED` — adds `levelEndTimeEpochMillis`, `nextSmallBlind` / `nextBigBlind`, `levelDurationSeconds`
- Frontend `tournament-detail.mapper.ts` — maps REST detail → `Tournament`; WS patch via `blindLevelUpdateFromWs`
- `BlindTimerComponent` — ring uses `levelDurationSeconds` when set

## Phase 4d — k6 load tests

Scripts under `load/k6/`:

| File | Purpose |
|------|---------|
| `tournament-table.js` | Smoke / load: auth → SNG register → hand → actions |
| `lib/auth.js`, `lib/tournament.js` | Shared REST helpers |
| `README.md` | Run instructions |

```bash
k6 run load/k6/tournament-table.js
k6 run -e SCENARIO=load -e VUS=20 -e DURATION=60s load/k6/tournament-table.js
```

For heavy runs set `rate-limit.enabled=false` on the target server.

Tournament hands set `player.userId` from registration `playerId` so JWT game actions work.

## Phase 4 complete

- 4a live hand, 4b chip sync, 4c blind timer, 4d k6 REST load
