# Running TruHoldem as a cluster (Phase 5)

`docker-compose.cluster.yml` boots a **two-node** TruHoldem cluster behind an nginx load balancer, on a
shared Postgres + Redis, with every Phase 5 feature turned on:

| Feature | Flag (env) | Cluster value |
|---|---|---|
| Per-table ownership (owner-gated timers) | `CLUSTER_OWNERSHIP_ENABLED` | `true` |
| Cross-node command routing (HTTP forward) | `CLUSTER_ROUTING_ENABLED` | `true` |
| Failover takeover (resume a dead owner's table) | `CLUSTER_TAKEOVER_ENABLED` | `true` |
| Fencing tokens (reject a stale owner's write) | `CLUSTER_FENCING_ENABLED` | `true` |
| WebSocket cluster broadcast | `WEBSOCKET_CLUSTER_ENABLED` | `true` |
| Fail-closed on Redis loss | `CLUSTER_FAIL_CLOSED` | `false` (fail-open) |

```
nginx :8090 ──ip_hash──► backend1 (truholdem-node-1)  ─┐
                         backend2 (truholdem-node-2)  ─┴─► Postgres + Redis (shared)
```

## Topology

- **nginx** (`docker/nginx/cluster.conf`) is the single entry point on host port **8090** (8080 is left to
  the single-node `docker-compose.yml`). It load-balances
  with `ip_hash` so each client sticks to one node (its long-lived WebSocket and REST calls land together),
  proxies the SockJS/STOMP upgrade, and returns **403** for `/api/internal/**` (node-to-node endpoints must
  not be reachable from clients).
- **backend1 / backend2** are the same image run twice. Each gets a distinct instance id from its hostname
  (`truholdem-node-1` / `-2`) and a peer-reachable `CLUSTER_NODE_BASE_URL` (`http://backendN:8080/api`,
  including the `/api` context-path) so the other node can forward actions to it. Nodes are also exposed
  directly on **8081** / **8082** for debugging.
- **Postgres + Redis** are shared. Redis holds the ownership leases, node registry, active-table set and
  fencing tokens; node 1 runs the Liquibase migration first (node 2 waits for it to be healthy).

## Run

```bash
docker compose -f docker-compose.cluster.yml up --build
```

- Cluster (via LB):   http://localhost:8090/api/actuator/health
- Frontend:           http://localhost:4200
- Node 1 directly:    http://localhost:8081/api/actuator/health
- Node 2 directly:    http://localhost:8082/api/actuator/health

Tear down (and wipe data):

```bash
docker compose -f docker-compose.cluster.yml down -v
```

## Verify it is really clustering

**1. Both nodes registered.** Each node writes `instanceId → base URL` into Redis on startup:

```bash
docker exec truholdem-cluster-redis redis-cli --scan --pattern 'truholdem:cluster:node:*'
# → truholdem:cluster:node:truholdem-node-1
#   truholdem:cluster:node:truholdem-node-2
```

**2. Per-table ownership.** Start a game and play a hand, then inspect the leases — each active table is
owned by exactly one node:

```bash
docker exec truholdem-cluster-redis redis-cli --scan --pattern 'truholdem:owner:*'
docker exec truholdem-cluster-redis redis-cli get truholdem:owner:<gameId>   # → truholdem-node-1 or -2
docker exec truholdem-cluster-redis redis-cli smembers truholdem:cluster:tables
```

**3. Cross-node routing.** Tail both nodes; an action that reaches the non-owner node is forwarded over
HTTP to the owner (look for `Forwarded action for game … to owner …`):

```bash
docker compose -f docker-compose.cluster.yml logs -f backend1 backend2 | grep -i "forward\|took over\|owner"
```

**4. Fencing tokens.** Each table has a monotonic token, bumped when ownership changes hands:

```bash
docker exec truholdem-cluster-redis redis-cli get truholdem:cluster:fence:<gameId>
```

**5. Kill-node failover.** With a hand in progress, stop the owning node and watch the survivor take over
the orphaned table and resume its timer (`Took over orphaned table …`):

```bash
docker compose -f docker-compose.cluster.yml stop backend1     # if node-1 owned the table
docker compose -f docker-compose.cluster.yml logs -f backend2 | grep -i "took over"
```

The game keeps playing through the LB — surviving clients were pinned to node 2, and node 2 re-acquired the
lease (after node 1's lease expired) and resumed the timer.

## Scaling benchmark

To measure the horizontal-scaling benefit on the action path (1 node vs 2 nodes behind a round-robin LB),
use the k6 harness — `docker-compose.scale.yml` + `load/k6/run-scaling.sh` (or `.ps1`). It plays many
independent bot games so the tables distribute across nodes. An example saturating run (VUS=30) showed
**~+60% throughput and lower action p95 on two nodes**; see [load/k6/README.md](../load/k6/README.md#scaling-benchmark--1-node-vs-2-nodes-game-actionsjs)
for the full numbers and an honest reading (CPU-bound bot AI; round-robin forces cross-node forwards that a
sticky `ip_hash` deployment avoids).

## Notes

- `CLUSTER_SHARED_SECRET` guards the node-to-node `/api/internal/cluster/**` endpoint (constant-time
  compare). Change it from the default before any non-local use.
- To harden against split-brain at the cost of availability, set `CLUSTER_FAIL_CLOSED=true`: a node that
  cannot reach Redis then refuses ownership instead of assuming it owns its tables.
- Scale beyond two nodes by copying a `backendN` service (new hostname + `CLUSTER_NODE_BASE_URL`) and adding
  it to the nginx `upstream`.
- This compose is for realistic local/integration runs. For production also pin image versions, supply
  secrets via a secret store, and front the LB with TLS.
