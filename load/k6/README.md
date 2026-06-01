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

---

# Scaling benchmark — 1 node vs 2 nodes (`game-actions.js`)

`game-actions.js` hammers the **action path**: each iteration creates an independent bot game and plays a
hand by driving player + bot actions over REST. Because every game is independent, a 2-node cluster spreads
the games (and their owners) across nodes, so this measures the horizontal-scaling benefit on the write
path that single-writer + cross-node routing serialize.

```bash
load/k6/run-scaling.sh                 # 1-node then 2-node, prints a comparison (VUS=30, 40s)
VUS=50 DURATION=60s load/k6/run-scaling.sh
```
```powershell
.\load\k6\run-scaling.ps1
```

The runner uses `docker-compose.scale.yml`: two nodes on a fresh Postgres + Redis with **game authorization
and rate limiting disabled** (so one token can drive many bot games), Hibernate-managed schema, and all
Phase 5 cluster flags on. Phase A drives node 1 directly (`:8081`); Phase B drives both nodes through a
**round-robin** nginx LB (`:8092`) — round-robin (not the production `ip_hash`) so a single load generator's
traffic actually spreads across both nodes.

### Measured result (example run, VUS=30, 40s, 8-core dev box)

| | 1 node | 2 nodes | Δ |
|---|---:|---:|---|
| HTTP throughput | 13.7 req/s | **21.9 req/s** | **+60%** |
| games created in window | 54 | 66 | +22% |
| `game_action` p95 | 6.9 s | 5.8 s | −16% |
| error rate | 12% | 20% | worse |

**Reading it honestly:** two nodes raise throughput ~60% and lower action latency, confirming the write path
scales horizontally. But this is an *intentionally saturating* load — the bot AI (Monte-Carlo) is
CPU-heavy, so at VUS=30 a single node is already CPU-bound, and under that pressure errors climb. The 2-node
error rate is actually *higher* for two test-harness reasons, not a clustering defect:

- **No sticky sessions in the benchmark.** Round-robin sends successive requests for the *same* game to
  whichever node, so ~half hit the non-owner and are forwarded over HTTP. Under saturation a fraction of
  those forwards time out (500 on `/internal/cluster/...`). A real deployment pins each client to one node
  (`ip_hash`), so its actions mostly reach the owner directly and are **not** forwarded.
- **Game creation saturates first.** Most errors are `POST …/game/start` 500s under load (DB/CPU pressure),
  not action failures — the same on 1 node (12%).

So treat these as **directional** numbers for relative comparison, re-runnable at other `VUS`/`DURATION`.
The clean takeaway: independent tables genuinely distribute across nodes and add capacity; pair the cluster
with sticky LB (`docker/nginx/cluster.conf`) in production to minimise cross-node forwarding.
