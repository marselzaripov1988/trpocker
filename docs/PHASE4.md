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

## Next (4b+)

- Sync registration chip counts after each hand
- Tournament blind timer → auto level advance
- Load tests (k6) for concurrent table actions
