# Phase 5a — Tournament detail API + lobby

## Goal

`GET /api/v1/tournaments/{id}` returns enough data for lobby and table UI without ad-hoc JSON shapes.

## API additions

| Field | Type | Description |
|-------|------|-------------|
| `players` | `LeaderboardEntryDto[]` | Standings (rank, playerId, name, chips, status, …) |
| `tables[].players` | `TablePlayerSummary[]` | Seated players per table (id, name, chips, isBot) |

Limits (configurable via `app.tournament`):

- `detail-max-registrations` (default **500**) — omit `players` when larger; use `GET .../leaderboard`
- `detail-max-tables` (default **100**) — omit table list when more active tables

## Backend

- `TournamentService.getTournamentDetail` loads registrations + table seats in one pass
- `TournamentDetailResponse.TablePlayerSummary` for table seats

## Frontend

- `tournament-detail.mapper.ts` maps `players` → `Tournament.registeredPlayers` and `tables[].players`
- Removed legacy merge hack in `TournamentStore`

## Tests

- `TournamentDetailServiceTest`
- `TournamentIT.TournamentDetailApiTests`
- `tournament-detail.mapper.spec` / `tournament.store.spec`

## Next (5b)

- Rebalance / elimination UX (`TABLE_REBALANCED`, `PLAYER_ELIMINATED`, `myTable` updates)
