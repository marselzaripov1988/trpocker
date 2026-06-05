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

---

# WebSocket capacity — cluster × N WS clients (`websocket-cluster.js`)

`websocket-cluster.js` opens a **fleet of long-lived STOMP-over-WebSocket subscribers** through the
round-robin LB and holds them open, then reports how the connections spread across the nodes. This targets
the one dimension a single instance can't carry: a 10k-player tournament isn't a CPU problem (the bot run
finishes in seconds — see `PyramidTournament10kIT`), it's a **concurrent-connection / memory** problem
(~1–2 MB heap per live WS session → ~15–50 GB for 10k on one JVM). The cluster's job is to spread those
sessions; this scenario measures that it does.

It talks **raw STOMP** to the non-SockJS endpoint at `addEndpoint("/ws")` (no SockJS framing). Auth is
optional — `WebSocketAuthInterceptor` allows anonymous read-only connections, which is exactly what a
"hold N subscribers + fan out broadcasts" test needs; pass `TOKEN` to connect authenticated.

```bash
load/k6/run-ws-cluster.sh                          # 500 connections, ramp 60s, hold 120s
CONNECTIONS=2000 HOLD=300s load/k6/run-ws-cluster.sh
SEED=1 CONNECTIONS=2000 load/k6/run-ws-cluster.sh  # also seed a 900-bot tournament → real broadcasts
KEEP=1 load/k6/run-ws-cluster.sh                   # leave the stack up
```
```powershell
.\load\k6\run-ws-cluster.ps1
$env:CONNECTIONS=2000; $env:SEED='1'; .\load\k6\run-ws-cluster.ps1
```

The runner boots `docker-compose.scale.yml` (two nodes, round-robin LB on `:8092`, all Phase 5 cluster flags
on), runs k6, then scrapes each node's `websocket_sessions_local` gauge + heap from `/actuator/prometheus`
and prints the split:

```
=== per-node distribution (at end of run) ===
  node-1   ws_sessions=1003   heap=512MB
  node-2   ws_sessions=997    heap=508MB
```

### Env

| Variable | Default | Description |
|----------|---------|-------------|
| `CONNECTIONS` | `500` | Target concurrent WS connections (k6 VUs) — push toward 10000 on real hardware |
| `RAMP` | `60s` | Ramp-up to `CONNECTIONS` |
| `HOLD` | `120s` | How long to hold the fleet open at full size |
| `SEED` | `0` | `1` → seed + start a 900-bot tournament so subscribers get real broadcasts |
| `TOURNAMENT_ID` | — | (script env) subscribe each VU to this tournament's topic + a table/shard topic |
| `TOKEN` | — | optional JWT sent on the STOMP CONNECT frame (otherwise anonymous) |
| `KEEP` | `0` | `1` → leave the stack up after the run |

### Measured signals

- **`ws_connect_success` / `ws_stomp_connected`** — handshake + STOMP CONNECT success rates (threshold > 95%).
- **`ws_connect_time`** — time from socket open to STOMP `CONNECTED`.
- **`ws_messages_received`** — broadcast frames delivered (meaningful only with `SEED=1` / `TOURNAMENT_ID`).
- **Per-node split** — the runner counts ESTABLISHED sockets to each node's `:8080` via `/proc/net/tcp`
  (`docker exec`). Note the app gauge `websocket_sessions_local` stays **0** for anonymous topic-only
  subscribers (it's incremented by `ClusterSessionRegistry.registerSession`, which fires for seated players,
  not bare spectators) — so the TCP count is the truthful live-connection metric here.

### Measured results (real run — 2-node round-robin stack on a 12-core / 25 GB dev box)

| Scenario | Per-node split | Connect success / errors | `ws_connect_time` | Mem/node (container) | CPU/node |
|---|---|---:|---|---:|---:|
| 1 500 connections | **750 / 750** | 100% (2999/2999) / 0 | avg 7 ms, p95 9 ms | 0.97 / 0.84 GiB | ~0.2% |
| 4 000 connections | **2000 / 2000** | 100% (7999/7999) / 0 | avg 6.7 ms, p95 8 ms | 1.11 / 1.02 GiB | ~0.25% |
| broadcast (1 000 subs, pyramid+bots) | — | 100% (1999/1999) / 0 | — | **195 634 msgs delivered, 1.3 GB egress / 70 s** |

Takeaways from the numbers:

- **Holding connections is cheap.** Going 1 500 → 4 000 added only ~150 MB/node → **~0.06 MB per bare
  subscriber** at the socket/STOMP layer; the ~0.8 GiB idle baseline dominates. One node holds tens of
  thousands of bare subscribers. CPU stays ~0 — confirming real (rarely-acting) players are light.
- **The LB spreads the fleet perfectly** (50/50 at both 1 500 and 4 000).
- **Broadcast fan-out is the real ceiling at scale.** 1 000 subscribers on one tournament-wide topic took
  **1.3 GB egress in 70 s** — linear to ~13 GB at 10 000. That's why per-table / `shard-count` topics exist:
  to send each event to one table's subscribers, not the whole field. The bare-connection memory figure
  (~0.06 MB) is *not* the 1–2 MB planning figure — the latter is for a **seated player** (game state + send
  buffers + game-topic payloads), which is the right number for sizing active players.

### Honest scope

- **Proven by this harness:** the cluster accepts and spreads a large WS fleet across nodes; per-node session
  counts and heap are observable; broadcasts fan out to subscribers.
- **OS-bound, not app-bound, toward 10k:** a single k6 host hits file-descriptor / ephemeral-port limits
  long before the app does — raise `ulimit -n` (≥ 65535) and run on adequately sized hosts. The LB
  (`docker/nginx/scale.conf`) is tuned to `worker_connections 32768` with long WS read timeouts.
- **Not yet run at a true 10k on production-sized infra** — the scenario is the instrument; an actual 10k
  sustained run needs a sized cluster (≥ 4–8 nodes), PgBouncer, and a multi-host load generator. That is the
  remaining ops exercise, distinct from the code being ready.

---

# Federated pyramid — wave-of-shards load (manual, scale cluster)

A federated pyramid is a very large field split into shards of `shardSize`, each an independent pyramid run
to one winner; the winners meet in an admin-scheduled final. Each shard is round-robin pinned to a
node-group (`app.tournament.federated-node-group-count`) — and because each shard is an ordinary tournament,
the lease-based cluster already spreads a federation's tables across nodes. To exercise a wave on the
2-node scale cluster (`docker-compose.scale.yml`, all Phase 5 flags on, auth disabled):

```bash
docker compose -f docker-compose.scale.yml up -d backend1 backend2 nginx
LB=http://localhost:8092
# create a federation (seats come from config; set federated-pyramid-enabled=true on the nodes)
FID=$(curl -s -X POST "$LB/api/v1/admin/pyramid-federations" -H 'Content-Type: application/json' \
  -d '{"name":"Wave","startingPlayers":1000,"shardSize":100}' | sed -n 's/.*"id":"\([0-9a-f-]\{36\}\)".*/\1/p')
# bulk-fill with bots (batched insert), then promote waves and drain shards to winners
curl -s -X POST "$LB/api/v1/admin/pyramid-federations/$FID/register-bots?count=1000" >/dev/null
curl -s -X POST "$LB/api/v1/admin/pyramid-federations/$FID/promote" >/dev/null
curl -s -X POST "$LB/api/v1/admin/pyramid-federations/$FID/drain-shards" >/dev/null
curl -s "$LB/api/v1/admin/pyramid-federations/$FID"   # watch shardsCompleted climb
```

Set `FEDERATED_PYRAMID_ENABLED=true` (and a smaller `app.tournament.pyramid-default-seats-per-table` for fast
bot rounds) on the backend nodes. Observe per-node table spread the same way as the WS scenario
(`websocket_sessions_local` / `docker stats`). **Honest scope:** node-group is balanced placement metadata +
an LB/ops hint today; pinning a shard's tables to its node-group at the engine level (vs. the existing
any-node lease ownership) is a documented follow-up.
