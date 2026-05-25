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
