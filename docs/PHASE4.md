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

## Next (4c+)
- Tournament blind timer → auto level advance
- Load tests (k6) for concurrent table actions
