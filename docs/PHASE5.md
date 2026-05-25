# Phase 5 — Tournament UX

## 5a — Tournament detail API + lobby

`GET /api/v1/tournaments/{id}` returns enough data for lobby and table UI without ad-hoc JSON shapes.

### API additions

| Field | Type | Description |
|-------|------|-------------|
| `players` | `LeaderboardEntryDto[]` | Standings (rank, playerId, name, chips, status, …) |
| `tables[].players` | `TablePlayerSummary[]` | Seated players per table (id, name, chips, isBot) |

Limits (configurable via `app.tournament`):

- `detail-max-registrations` (default **500**) — omit `players` when larger; use `GET .../leaderboard`
- `detail-max-tables` (default **100**) — omit table list when more active tables

### Backend

- `TournamentService.getTournamentDetail` loads registrations + table seats in one pass
- `TournamentDetailResponse.TablePlayerSummary` for table seats

### Frontend

- `tournament-detail.mapper.ts` maps `players` → `Tournament.registeredPlayers` and `tables[].players`
- Removed legacy merge hack in `TournamentStore`

### Tests

- `TournamentDetailServiceTest`
- `TournamentIT.TournamentDetailApiTests`
- `tournament-detail.mapper.spec` / `tournament.store.spec`

---

## 5b — Rebalance / elimination via WebSocket

Incremental patches in `TournamentStore` instead of debounced full `loadTournament` for common events.

### WS → store

| Message | Incremental patch | Fallback |
|---------|-------------------|----------|
| `BLIND_LEVEL_INCREASED` | `blindLevelUpdateFromWs` | full refresh |
| `PLAYER_ELIMINATED` | `playerEliminatedFromWs` | full refresh |
| `TABLE_REBALANCED` | `tableRebalancedFromWs` (+ WS table resubscribe) | full refresh when target table unknown |
| `FINAL_TABLE_REACHED` | status `FINAL_TABLE` | full refresh if bundled with moves |
| `TOURNAMENT_STARTED` | status `RUNNING` when was registering | full refresh |
| `TOURNAMENT_COMPLETED` | status `FINISHED` + delayed refresh | — |

### Mappers

- `tournament-ws.mapper.ts` — `playerEliminatedFromWs`, `tableRebalancedFromWs`, `finalTableReachedFromWs`
- Updates `registeredPlayers`, `tables`, `remainingPlayers`, `myPlayer`, `myTable`
- `lastUpdate` drives status banner on tournament table (`data-cy="tournament-status-banner"`)

### Tests

- `tournament-ws.mapper.spec.ts`
- `tournament.store.spec.ts` (WS handler describe)

---

## 5c — CI k6 smoke

GitHub Actions job **`k6-smoke`** (after `backend-test`):

- Stack: `docker-compose.k6.yml` — Postgres, Redis, backend on `:8080`
- `RATE_LIMIT_ENABLED=false`, `SETUP_STAGGER_SECONDS=0` for fast setup
- Artifact: `k6-smoke-summary.json`

Does not block Docker image build (parallel with `e2e-test`).

### Next (5d)

- E2E tournament flow (Cypress)
