# Kubernetes — PYRAMID 10k

Use **managed PostgreSQL** (RDS, Cloud SQL, Azure Database) in production; manifests below cover app tier + Redis + ingress.

## Apply order

```bash
kubectl apply -f namespace.yaml
# Edit secret-template.yaml → secret.yaml (do not commit real secrets)
kubectl apply -f secret.yaml
kubectl apply -f configmap-backend.yaml
kubectl apply -f redis.yaml
kubectl apply -f deployment-backend.yaml
kubectl apply -f service-backend.yaml
kubectl apply -f hpa-backend.yaml
kubectl apply -f ingress.yaml
```

## External PostgreSQL

Set in `secret.yaml`:

```yaml
SPRING_DATASOURCE_URL: jdbc:postgresql://your-pgbouncer:6432/truholdem
```

Recommended: **PgBouncer** in transaction mode, pool size 40 per app pod × 8 pods = 320 → set PgBouncer `default_pool_size` ~50, `max_client_conn` 500.

## Scaling

| Resource | Initial | HPA max |
|----------|---------|---------|
| backend Deployment | 8 replicas | 16 |
| Redis | 1 (use Redis Operator / Elasticache for prod) | — |

## Session affinity

`ingress.yaml` uses nginx annotation `affinity: cookie` — required for WebSocket unless `WEBSOCKET_CLUSTER_ENABLED=true` on all pods (still recommended).

See [docs/PYRAMID_10K_PRODUCTION.md](../../docs/PYRAMID_10K_PRODUCTION.md) for full checklist.
