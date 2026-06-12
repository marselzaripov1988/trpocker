# PYRAMID 10 000 — production infrastructure

Guide for running a **real-player** pyramid tournament (`10 000 → 1 000 → 100 → 10 → 1`) on TruHoldem.

> **Note:** The automated test `PyramidTournament10kIT` (~40 s, passive bots, no WebSocket) validates **game logic**, not production capacity. Plan infrastructure using the tables below.

---

## Architecture

```text
                    ┌──────────────┐
                    │ CDN / static │  Angular frontend
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │ Load balancer │  sticky sessions / WS affinity
                    │ (Traefik/nginx│
                    │  or Ingress)  │
                    └──────┬───────┘
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
    ┌────────────┐  ┌────────────┐  ┌────────────┐
    │ Backend ×8 │  │ Backend …  │  │ Backend …  │  Spring Boot 3.5
    └─────┬──────┘  └─────┬──────┘  └─────┬──────┘
          │               │               │
          └───────────────┼───────────────┘
                          │
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
   ┌────────────┐  ┌────────────┐  ┌──────────────┐
   │ PgBouncer  │  │   Redis    │  │  Prometheus  │
   │ (pool)     │  │  Cluster   │  │  + Grafana   │
   └─────┬──────┘  └────────────┘  └──────────────┘
         ▼
   ┌────────────┐     ┌────────────┐
   │ PostgreSQL │────►│ Read replica│  leaderboard / reports
   │  primary   │     └────────────┘
   └────────────┘
```

---

## Sizing (real players)

| Tier | App nodes | PostgreSQL | Redis | When |
|------|-----------|------------|-------|------|
| **Pilot** | 4 × (8 vCPU, 16 GB) | 16 vCPU, 64 GB | 3 × 8 GB | &lt; 3 000 concurrent |
| **Recommended** | **8 × (8 vCPU, 16 GB)** | **32 vCPU, 128 GB** + replica | **6 × 8 GB** cluster | **10 000 players** |
| **Comfort** | 12 × (8 vCPU, 16 GB) | Managed PG large + 2 replicas | 6 × 16 GB | TV stream, heavy analytics |

**Bottlenecks (in order):**

1. **~10 000 WebSocket** connections (memory + file descriptors).
2. **PostgreSQL** if every action is persisted (use `persist-on-hand-end-only=true`).
3. **Redis** for hot state + WS pub/sub (`WEBSOCKET_CLUSTER_ENABLED=true`).
4. CPU of poker engine (usually OK with human think times).

---

## Reference cluster topology (for provider price comparison)

Maps the **Recommended (10 000-player)** sizing onto **physical servers** so providers can be quoted
apples-to-apples. Pick the shape that matches your hosting route (see
[DEPLOYMENT.md → Hosting provider selection](DEPLOYMENT.md#hosting-provider-selection)). The signer is
**air-gapped and off-cluster** — none of these nodes hold private keys.

> Heads-up: "8 servers" in the compose file is only the **app tier** (`--scale backend=8`). A self-managed
> cluster also needs DB, Redis, LB and ops nodes, so the real box count is higher unless you take managed
> services (variant C).

### A) Packed — exactly 8 dedicated servers (offshore/crypto route, self-managed, min box count)

Consolidates roles to hit 8 boxes; less blast-radius isolation. Workable for launch / cost-sensitive.

| # | Node | Specs | Runs |
|---|------|-------|------|
| 1 | `edge-1` | 8 vCPU / 16 GB / ≥1 Gbps | LB (HAProxy/Traefik, **WS-sticky**) + TLS + frontend origin |
| 2–5 | `app-1..4` | 16 vCPU / 32 GB / NVMe | **2× backend each = 8 instances** + PgBouncer; one Redis node co-resident |
| 6 | `db-1` | 32 vCPU / 128 GB / 2×NVMe RAID1 | PostgreSQL **primary** |
| 7 | `db-2` | 16 vCPU / 64 GB / NVMe | PostgreSQL **replica** + backup target |
| 8 | `infra-1` | 8 vCPU / 32 GB / NVMe | Redis cluster anchor + Prometheus/Grafana + MinIO (KYC) |

Redis = 6-node cluster spread across `app-1..4` + `infra-1` (+`edge-1`). **Trade-off:** Redis/LB co-resident with
app → a node loss hits more than one role. Needs a private VLAN; DDoS in front of `edge-1`.

### B) Clean — isolated roles (~14–16 dedicated servers, self-managed, recommended for go-live)

| Role | Count | Per-node specs |
|------|:--:|---|
| LB / edge (HA pair) | 2 | 8 vCPU / 16 GB |
| Backend app | 8 | 8 vCPU / 16 GB |
| PostgreSQL (primary + replica) | 2 | 32/128 + 16/64, NVMe |
| Redis cluster | 3 | 8 vCPU / 16 GB (3 master + co-resident replicas) |
| Ops (Prometheus/Grafana + PgBouncer + backups + MinIO) | 1 | 8 vCPU / 32 GB |

≈ **16 nodes**, best isolation/HA. This is the bare-metal/colocation target for FlokiNET/AbeloHost/Cherry Servers.

### C) Managed — fewest self-run boxes (OVH / iGaming specialist)

| Component | What you rent | You manage? |
|-----------|---------------|:--:|
| Backend app | 8 compute nodes **or** a managed-k8s pool (≈64 vCPU / 128 GB total) | pods only |
| PostgreSQL | Managed PG (32 vCPU / 128 GB + read replica) | ✗ |
| Redis | Managed Redis cluster (≥48 GB) | ✗ |
| Load balancer | Managed **WS-aware** LB (1 public VIP) | ✗ |
| DDoS / WAAP | Included | ✗ |
| Object storage | Managed S3-compatible (KYC docs) | ✗ |

You operate ~**8 compute nodes**; DB/Redis/LB/storage are managed → fewest moving parts. Best for the licensed
route (OVH vRack + Managed Kubernetes/DB; Continent 8 / MassiveGRID IaaS).

### Quote checklist (paste to each provider for apples-to-apples pricing)

- **N nodes** with the specs above, **all in one datacenter**.
- **Private L2/VLAN** across all nodes, intra-cluster latency target **< 1 ms** (Redis pub/sub, PG replication, WS cluster).
- **WebSocket-aware load balancer** (managed, or capacity to run HAProxy) — 1 public VIP, sticky sessions ≥ 3 h.
- **Always-on DDoS (L3/4 + L7)** — confirm **WebSocket pass-through** and **added latency** under scrubbing.
- **NVMe** on DB nodes (RAID1 on primary); daily snapshot/backup target + egress allowance.
- **Bandwidth:** ≥ 1 Gbps/node + monthly transfer allowance (10 k WS is light on bytes but bursty).
- **Burst/headroom:** for federated tournaments → quote the unit price to **add an 8-node node-group** on demand
  (1M-player federations shard horizontally across node-groups, pinned round-robin).

> The 1M federated tier is **not** this cluster — it scales by adding node-groups. Price the "+1 node-group" unit
> so you can model tournament-day capacity, not just the 10 k baseline.

### Sizing by shard size

Hardware is driven by the **peak concurrent WebSocket count = the round-1 player count** (the memory + file-descriptor
bottleneck). The **number of rounds does not change the fleet** — it only changes duration (more rounds × 3 hands =
longer). So size the farm to the shard size, not to how deep the pyramid goes.

**Rule of thumb:** ~**1 backend node per ~1 250–2 000 concurrent players** + one PostgreSQL + one Redis + an LB,
with HA minimums (always ≥ 2 backend, a PG primary, a Redis). PG/Redis scale ~linearly with the peak.

| Shard (peak WS) | Pyramid shape | Backend | PostgreSQL | Redis | LB/ops | ~ nodes | ~ $/mo (OVH-class) |
|---|---|---|---|---|---|:--:|---|
| **1 000** | 10³ (3 rounds @ 10) | 2 × (8c/16 GB) *(2nd = HA)* | 1 × (8–16c / 32–64 GB), replica opt. | 1 × 8 GB (or co-res) | folded | **2–3** | **$200–400** |
| **6 561** | 9⁴ (4 rounds @ 9) or 3⁸ (8 @ 3) | 5 × (8c/16 GB) **or** 3 × (16c/48 GB)×2 JVM | 1 × (16–24c / 64–96 GB) + opt. replica | 4 × 8 GB | 1 edge + folded ops | **5–6** | **$600–1 000** |
| **10 000** | ≈10⁴ (4 rounds @ 10) | 8 × (8c/16 GB) | 32c / 128 GB + replica | 6 × 8 GB | edge + infra | **8** | **$1 000–1 500** |

Smaller shards are the **hardware ↔ shard-count ↔ duration** lever: a 1 000-shard farm is cheap but a 1M field is
**1 000 shards** of 3 rounds each; a 10 000-shard farm costs more but it's only **100 shards** of 4 rounds. 6 561 is
a clean middle (perfect power → pyramids to 1; 1M ≈ 153 shards). Round count affects only the **per-shard time**
(4 rounds ≈ 1–2 h at human pace, `pyramidDefaultHandsPerRound=3`, 30 s/action; bots ≈ seconds).

> Figures are an engineering starting point from the ~1–2 MB/WS, 8–16 GB heap per 2–3 k connections heuristic above.
> The real "WS per node" ceiling is confirmed only by a WebSocket load test (`load/k6`), not on paper.

---

## Docker Compose (lab / staging)

Files:

- [`docker-compose.pyramid-prod.yml`](../docker-compose.pyramid-prod.yml) — Postgres, PgBouncer, Redis, Traefik, scalable backend.
- [`docker/traefik/pyramid-dynamic.yml`](../docker/traefik/pyramid-dynamic.yml) — sticky sessions for WebSocket.
- Profile `application-pyramid-prod` — tuned Spring settings.

### Start (8 backend replicas)

```bash
# From repo root
export JWT_SECRET="$(openssl rand -base64 48)"
export DB_PASSWORD="change-me-strong"

docker compose -f docker-compose.pyramid-prod.yml build backend
docker compose -f docker-compose.pyramid-prod.yml up -d \
  postgres pgbouncer redis traefik frontend

docker compose -f docker-compose.pyramid-prod.yml up -d --scale backend=8

# Health
curl -s http://localhost/api/actuator/health | jq .
```

| URL | Service |
|-----|---------|
| http://localhost | Frontend + API via Traefik |
| http://localhost:8080/api/actuator/prometheus | Metrics (direct, debugging) |

### Pyramid tournament (API)

1. Create: `POST /api/v1/tournaments` with `"type": "PYRAMID"`, `maxPlayers: 10000`.
2. Register players (or batch endpoint via admin tool).
3. Start tournament, then run **pyramid director** (`PyramidTournamentService.runToCompletion` — today via internal job/test; expose as admin API before go-live).

Env overrides (see compose file):

```bash
APP_GAME_HOT_STATE_ENABLED=true
APP_GAME_PERSIST_ON_HAND_END_ONLY=true
APP_TOURNAMENT_PYRAMID_TABLE_PARALLELISM=32
WEBSOCKET_CLUSTER_ENABLED=true
```

---

## Kubernetes (production sketch)

Manifests: [`k8s/pyramid-10k/`](../k8s/pyramid-10k/).

```bash
kubectl apply -f k8s/pyramid-10k/namespace.yaml
kubectl apply -f k8s/pyramid-10k/configmap-backend.yaml
kubectl apply -f k8s/pyramid-10k/secret-template.yaml   # fill secrets first
kubectl apply -f k8s/pyramid-10k/redis.yaml
kubectl apply -f k8s/pyramid-10k/deployment-backend.yaml
kubectl apply -f k8s/pyramid-10k/hpa-backend.yaml
kubectl apply -f k8s/pyramid-10k/ingress.yaml
# PostgreSQL: use managed service (RDS/Cloud SQL) — see README in k8s folder
```

**Ingress:** enable **session affinity** (cookie `truholdem-route` or `client-ip` timeout ≥ 3 h).

**HPA:** scale backend on CPU **and** custom metric `truholdem_websocket_connections` if exposed.

---

## Application checklist (before go-live)

### Must have

- [ ] **≥ 8** backend instances behind LB with **WS sticky** or Redis relay (`app.websocket.cluster.enabled=true`).
- [ ] **PgBouncer** (or RDS Proxy): pool 300–500 DB connections, app pool ≤ 30 per instance.
- [ ] **PostgreSQL** 32+ vCPU, NVMe; `max_connections` aligned with PgBouncer.
- [ ] **Redis** cluster; `spring.cache.type=redis`, hot state on (`app.game.hot-state-enabled=true`).
- [ ] **Persist on hand end only** (`app.game.persist-on-hand-end-only=true`).
- [ ] **PYRAMID** director as **background job** (not only JUnit), idempotent round advance.
- [ ] **Leaderboard API** paginated; no full 10 k list in `GET /tournaments/{id}` detail.
- [ ] **Rate limits** on register/login; CDN for frontend static assets.
- [ ] **Load test:** 10 k WebSocket + registration (k6/Gatling), not only bot simulation.
- [ ] **Alerts:** DB connections, Redis memory, p95 API latency, WS disconnect rate, 5xx rate.

### Recommended

- [ ] Read replica for leaderboard / history queries.
- [ ] `app.tournament.async-start-threshold=500` (already default) — async table creation for 10 k.
- [ ] `app.tournament.shard-count=16` — WS topic sharding.
- [ ] Disable verbose bot decision logging (`logging.level.com.truholdem.service.PokerGameService=WARN`).
- [ ] Runbook: pause tournament, cancel stuck tables, force `endTournament`.
- [ ] Backup PG before event; rehearse restore.

### Product / rules

- [ ] `handsPerRound` and tie-break (equal stacks) documented for players.
- [ ] `seatsPerTable=10` confirmed in UI and API.
- [ ] Estimated duration communicated (~1–2 h for 4 rounds with human pace).

---

## Capacity math (round 1)

| Metric | Value |
|--------|-------|
| Players | 10 000 |
| Tables | 1 000 |
| WS connections | ~10 000 |
| Peak active games | ~1 000 (staggered, not 1 000 CPU-bound seconds) |
| Registrations rows | 10 000 |
| Eliminations per pyramid step | 9 000 (round 1) |

**RAM hint:** ~1–2 MB per WebSocket on JVM → **8–16 GB heap per 2 000–3 000** connections → **8 nodes × 12 GB heap** is a safe starting point.

---

## Related docs

- [WEBSOCKET-CLUSTER-DEPLOYMENT.md](WEBSOCKET-CLUSTER-DEPLOYMENT.md) — Redis pub/sub clustering
- [DEPLOYMENT.md](DEPLOYMENT.md) — general deployment
- [TOURNAMENTS.md](TOURNAMENTS.md) — tournament types
- Bot load test: `PyramidTournament10kIT` with `-Dpyramid10k=true`
