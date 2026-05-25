# k6 — Tournament table load tests (Phase 4d)

REST scenario: auth → register for tournament → start hand → player actions.

## Prerequisites

1. Backend running on `http://localhost:8080` (Postgres + Redis as usual).
2. [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/) installed.

For sustained load, disable rate limiting on the target server:

```properties
rate-limit.enabled=false
```

Or set in environment when starting Spring Boot.

## Quick run

From repo root (backend on `:8080`, Postgres/Redis up):

```bash
# Smoke — 4 players, one hand each (~2 min: setup staggers auth for rate limits)
k6 run load/k6/tournament-table.js

# Load — ramp to 20 VUs for 60s
k6 run -e SCENARIO=load -e VUS=20 -e DURATION=60s load/k6/tournament-table.js
```

Windows PowerShell:

```powershell
k6 run load/k6/tournament-table.js
k6 run -e SCENARIO=load -e VUS=20 -e DURATION=60s load/k6/tournament-table.js
```

## Environment

| Variable | Default | Description |
|----------|---------|-------------|
| `BASE_URL` | `http://localhost:8080` | Backend origin (context path `/api` is appended in scripts) |
| `SCENARIO` | `smoke` | `smoke` (4 VU × 1 iter) or `load` (ramping VUs) |
| `VUS` | `4` | Players per tournament (= `maxPlayers`) |
| `DURATION` | `60s` | Hold phase for `load` scenario |
| `K6_PASSWORD` | `LoadTest123!` | Password for auto-registered users |
| `ACTION_ROUNDS` | `30` | Max actions per VU per iteration |
| `SETUP_STAGGER_SECONDS` | `13` | Delay between setup registrations (`0` when rate limit is off, e.g. CI) |

## CI (GitHub Actions)

Job `k6-smoke` in `.github/workflows/ci-cd.yml`:

- Starts `docker-compose.k6.yml` (Postgres, Redis, backend with `RATE_LIMIT_ENABLED=false`)
- Runs `k6 run load/k6/tournament-table.js` with `SETUP_STAGGER_SECONDS=0`
- Uploads `k6-smoke-summary.json` artifact on completion

Local reproduction:

```bash
docker compose -f docker-compose.k6.yml up -d --build
# wait for http://localhost:8080/api/actuator/health
SETUP_STAGGER_SECONDS=0 k6 run load/k6/tournament-table.js
docker compose -f docker-compose.k6.yml down -v
```

## What is measured

- `auth_register`, `auth_login`, `tournament_*`, `game_action` request durations
- Thresholds: error rate & p95 latency (see `tournament-table.js`)

## Notes

- Setup creates one SNG; each VU registers a unique user (profile UUID = tournament `playerId`).
- When `registeredPlayers === maxPlayers`, the tournament auto-starts.
- Human actions require JWT ownership (`player.userId` set from registration id).
- Cluster WebSocket load: see `load-test/load-test-cluster.sh` (separate from this REST suite).
