# TruHoldem Deployment Guide

Production deployment and operations guide for the TruHoldem poker platform.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Pyramid 10k production](#pyramid-10k-production)
- [Quick Start](#quick-start)
- [Development Setup](#development-setup)
- [Docker Deployment](#docker-deployment)
- [Hosting provider selection](#hosting-provider-selection)
- [Production Configuration](#production-configuration)
- [CI/CD Pipeline](#cicd-pipeline)
- [Monitoring & Observability](#monitoring--observability)
- [Scaling](#scaling)
- [Troubleshooting](#troubleshooting)

---

## Prerequisites

| Component | Version | Purpose |
|-----------|---------|---------|
| Docker | 24+ | Containerization |
| Docker Compose | 2.20+ | Multi-container orchestration |
| Java | 21 | Backend runtime |
| Node.js | 20+ | Frontend build |
| PostgreSQL | 16 | Primary database |
| Redis | 7 | Caching & WebSocket pub/sub |

---

## Pyramid 10k production

For **10 000 real players** (PYRAMID tournament: WebSocket cluster, PgBouncer, multi-backend):

- Guide: [PYRAMID_10K_PRODUCTION.md](PYRAMID_10K_PRODUCTION.md)
- Docker Compose: `docker compose -f docker-compose.pyramid-prod.yml up -d --scale backend=8`
- Kubernetes: [k8s/pyramid-10k/](../k8s/pyramid-10k/)

---

## Quick Start

One-command deployment with Docker Compose:

```bash
# Clone and start
git clone https://github.com/yourusername/truholdem.git
cd truholdem
docker-compose up -d
```

Access points after startup:

| Service | URL | Credentials |
|---------|-----|-------------|
| Frontend | http://localhost:4200 | - |
| API | http://localhost:8080/api | - |
| Swagger UI | http://localhost:8080/api/swagger-ui.html | - |
| Grafana | http://localhost:3000 | admin/admin |
| Prometheus | http://localhost:9090 | - |
| Jaeger UI | http://localhost:16686 | - |

---

## Development Setup

### Backend

```bash
cd backend

# Install dependencies and run
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Or with specific database
DB_USERNAME=user DB_PASSWORD=password ./mvnw spring-boot:run
```

Required services for local development:

```bash
# Start only infrastructure
docker-compose up -d postgres redis
```

### Frontend

```bash
cd frontend

# Install dependencies
npm ci

# Development server with hot reload
npm run dev

# Build for production
npm run build
```

### Configuration Files

Backend configuration hierarchy:

```
application.properties          # Base config
application-dev.properties      # Development overrides
application-docker.properties   # Docker environment
application-prod.properties     # Production settings
application-test.properties     # Test configuration
```

Key environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | dev | Active Spring profile |
| `DB_USERNAME` | user | Database username |
| `DB_PASSWORD` | password | Database password |
| `REDIS_HOST` | localhost | Redis server host |
| `JWT_SECRET` | - | JWT signing key (min 32 chars) |
| `CORS_ORIGINS` | localhost:4200 | Allowed CORS origins |
| `OTEL_ENABLED` | true | Enable OpenTelemetry |

---

## Docker Deployment

### Building Images

```bash
# Build backend image
docker build -t truholdem-backend:latest ./backend

# Build frontend image
docker build -t truholdem-frontend:latest ./frontend \
  --build-arg API_URL=https://api.yourdomain.com
```

### Docker Compose Services

The `docker-compose.yml` orchestrates:

| Service | Image | Port | Purpose |
|---------|-------|------|---------|
| postgres | postgres:16-alpine | 5432 | Primary database |
| redis | redis:7-alpine | 6379 | Cache & pub/sub |
| backend | custom | 8080 | Spring Boot API |
| frontend | custom | 4200 | Angular SPA |
| nginx | nginx:alpine | 80, 443 | Reverse proxy |
| prometheus | prom/prometheus | 9090 | Metrics collection |
| grafana | grafana/grafana | 3000 | Dashboards |
| jaeger | jaegertracing/all-in-one | 16686 | Distributed tracing |
| otel-collector | otel/opentelemetry-collector | 4317 | Telemetry pipeline |

### Health Checks

All services include health checks:

```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/api/actuator/health"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 60s
```

---

## Hosting provider selection

Real-money **crypto** poker is an unusual hosting workload: mainstream clouds (AWS/GCP/Azure) routinely suspend
gambling and crypto businesses per ToS, the platform is a prime DDoS-extortion target, it is WebSocket-heavy and
latency-sensitive, and tournaments are bursty (federated pyramids scale toward 1M players). One mitigating factor:
**signing is offline / air-gapped** (see [ISOLATED_CUSTODY_FEDERATION.md](ISOLATED_CUSTODY_FEDERATION.md) §0), so
the production host is **watch-only and never holds hot keys** — which lowers the trust you must place in any host
and makes smaller providers viable.

### Selection criteria (weighted for this platform)

| Need | Why it matters here | Bar |
|---|---|---|
| Gambling + crypto ToS tolerance | Don't get deplatformed mid-tournament; hosting must be paid in crypto if desired | Hard requirement |
| DDoS: **L7 + WebSocket-safe + always-on** | Poker is heavily targeted; long-lived WS connections + latency-sensitive action timers | Hard requirement; validate live |
| Managed stateful services *or* capacity to self-run | Code needs Postgres HA/PITR, Redis cluster, S3/MinIO (KYC), k8s (`k8s/pyramid-10k`) | Decide build-vs-buy explicitly |
| Elastic / burst headroom | Tournament spikes (`pyramid-10k` → 1M) vs fixed dedicated capacity | Overprovision or autoscale |
| SLA + 24/7 ops | It moves player funds; downtime = disputes + lost money | ≥99.9%, real response SLA |
| Jurisdiction + KYC data residency | Encrypted KYC video/docs stored; EEA = GDPR | Match licence + GDPR |
| Latency to player geography | WS action timers; scrubbing adds latency | Test from target regions |

> **Hosting ≠ a gambling licence.** Offshore hosting does not legalise real-money poker — a licence
> (Curaçao / Anjouan / Kahnawake / MGA / etc.) is the gating legal step and drives KYC/AML obligations and where
> you may operate. Decide the licence route first; it constrains the host more than the reverse.

### Candidate providers (better-fit than a generic privacy host)

**Route A — licensed/regulated operator (recommended for real-money at scale).** iGaming-specialist *managed*
hosts that fill the gaps a privacy host leaves (managed services, real SLAs, game-aware DDoS, compliance):

| Provider | Fit | Notes |
|---|---|---|
| [Continent 8](https://www.continent8.com/) | Premium, compliance-first | Purpose-built iGaming, 100+ locations/4 continents, WAAP + DDoS, **Jurisdiction-as-a-Service** / regulated cloud, 25+ yrs |
| [MassiveGRID](https://massivegrid.com/igaming-gambling-managed-hosting/) | Managed + SLA | MGA/UKGC compliance, **10+ Tbps** DDoS with iGaming traffic profiling, 100% uptime SLA |
| [Internet Vikings](https://internetvikings.com/) | Regulated markets (esp. US) | Cloudflare DDoS (claimed 230+ Tbps), bare metal across continents |
| [NovoServe](https://novoserve.com/igaming) | Bare-metal iGaming | Multi-Tbps in-house *game-aware* scrubbing, EU/US |

**Route B — crypto-native / offshore (FlokiNET-style, but stronger ops).** Crypto-paying bare metal with better
support/DDoS; you self-manage the full stack:

| Provider | Fit | Notes |
|---|---|---|
| [Cherry Servers](https://www.cherryservers.com/blog/dedicated-servers-with-ddos-protection) | Crypto-native bare metal | 15+ coins, DDoS, **24/7 human support** + dedicated manager — "FlokiNET with better ops" |
| [COIN.HOST](https://coin.host/ddos-protection/crypto) | Crypto + DDoS | Accepts BTC/USDT/ETH/…, 110% SLA-backed DDoS |
| [OVHcloud](https://us.ovhcloud.com/security/game-ddos-protection/) | Pragmatic middle | Included game-tuned multi-Tbps anti-DDoS, bare metal **+ managed DBs/k8s**, mature global, generally gambling-tolerant — best ops-maturity/cost balance |

### Comparison matrix

Legend: ✓ yes · ~ partial/conditional · ✗ no · ? unconfirmed (verify with provider). Cells reflect public
AUP/positioning at time of research — **confirm the licence-gate and crypto-payment columns directly.**

| Provider | Gambling OK | No licence gate¹ | Pay in crypto | WS-safe DDoS² | Managed svcs³ | SLA / ops | iGaming compliance⁴ |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| FlokiNET | ✓ | ✓ | ✓ | ~? | ✗ | ~ | ✗ |
| Cherry Servers | ~? | ✓ | ✓ | ~ | ~ | ✓ | ✗ |
| COIN.HOST | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | ✗ |
| OVHcloud | ~ | ~ | ✗ | ✓ | ✓ | ✓ | ~ |
| NovoServe | ✓ | ~ | ? | ✓ | ✗ | ✓ | ~ |
| Internet Vikings | ✓ | ✗ | ? | ✓ | ~ | ✓ | ✓ |
| MassiveGRID | ✓ | ✗ | ? | ✓ | ✓ | ✓ | ✓ |
| Continent 8 | ✓ | ✗ | ? | ✓ | ✓ | ✓ | ✓✓ |

¹ **No licence gate** = the provider does not require proof of a gambling licence to onboard (per public
AUP/positioning). This is **provider onboarding policy, not legality** — running real-money gambling without a
licence is a legal risk regardless of host, and every provider still acts on court orders / abuse reports. The
iGaming specialists are compliance-first and typically **expect a licence** (✗ here).
² WebSocket-safe / game-aware DDoS scrubbing (not just volumetric L3/L4). ³ Managed Postgres/Redis/object
storage/k8s vs self-run on bare metal. ⁴ Built-in regulated-market compliance (MGA/UKGC/JaaS).

> **The matrix shows the core trade-off:** "no licence gate" (offshore/crypto hosts, top rows) and
> "managed + SLA + iGaming compliance" (specialists, bottom rows) sit at **opposite ends** — you rarely get both.
> Pick the row band that matches your licensing strategy; the air-gapped custody is what makes the top band
> tolerable security-wise.

**Protection layer (pair with any host).** [Cloudflare Spectrum / Magic Transit](https://www.cloudflare.com/)
(WebSocket-safe L4/L7 DDoS + proxy) or **Path.net** decouple DDoS quality from the host — useful if you otherwise
prefer a cheaper/offshore box. Validate WebSocket pass-through + added latency.

### Worked example — FlokiNET (flokinet.is)

Viable for the **crypto / offshore / gambling** model: AUP doesn't prohibit gambling, accepts crypto (BTC/XMR/…),
anti-DDoS included (separate "1Tbps+" tier), privacy jurisdictions (Iceland/NL/Finland/Romania); and the air-gapped
custody means it never holds keys. **Gaps:** no managed services (you self-run Postgres/Redis/MinIO/k8s), no
autoscale for tournament bursts, thinner SLA/support (Signal/Threema/email), and DDoS efficacy + WebSocket-safety
are **unverified**. Verdict: usable for the **stateful core + strict DR**, but validate DDoS/SLA live and put a
WebSocket-safe scrubbing layer in front.

### Decision guide

- **Licensed real-money at scale** → iGaming specialist (Continent 8 / MassiveGRID / Internet Vikings / NovoServe).
- **Crypto-offshore + cost, willing to self-manage** → Cherry Servers / COIN.HOST / OVH, fronted by Cloudflare Spectrum.
- **Always:** licence first · never single-home a money platform (multi-DC or second provider for DR) ·
  load-test (`load/k6` + a tournament simulation) against a trial box before migrating.

> Vendor Tbps/uptime figures are **marketing** — validate scrubbing capacity, L7/WebSocket behaviour, always-on vs
> on-demand, and added latency yourself before committing.

**Sources:** [iGaming hosting guide (RedSwitches)](https://www.redswitches.com/blog/best-igaming-server-hosting-providers/) ·
[Online casino hosting (HostAdvice)](https://hostadvice.com/offshore-hosting/online-casino-hosting/) ·
[Continent 8](https://www.continent8.com/) · [MassiveGRID](https://massivegrid.com/igaming-gambling-managed-hosting/) ·
[Internet Vikings](https://internetvikings.com/) · [NovoServe](https://novoserve.com/igaming) ·
[Cherry Servers — DDoS dedicated](https://www.cherryservers.com/blog/dedicated-servers-with-ddos-protection) ·
[COIN.HOST](https://coin.host/ddos-protection/crypto) · [OVHcloud game DDoS](https://us.ovhcloud.com/security/game-ddos-protection/).

---

## Production Configuration

### Security Checklist

- [ ] Generate strong JWT secret: `openssl rand -base64 64`
- [ ] Change default database credentials
- [ ] Configure CORS for production domains only
- [ ] Enable HTTPS with valid certificates
- [ ] Set `SPRING_PROFILES_ACTIVE=prod`
- [ ] Disable Swagger in production
- [ ] Configure firewall rules

### Environment Variables (Production)

```bash
# Security
JWT_SECRET=$(openssl rand -base64 64)
DB_PASSWORD=$(openssl rand -base64 32)

# Database
DB_USERNAME=truholdem_prod
SPRING_DATASOURCE_URL=jdbc:postgresql://db.internal:5432/truholdem

# Application
SPRING_PROFILES_ACTIVE=prod
CORS_ORIGINS=https://yourdomain.com
WEBSOCKET_ORIGINS=https://yourdomain.com

# Observability
OTEL_SERVICE_NAME=truholdem-prod
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
```

### SSL/TLS Configuration

Nginx SSL configuration example:

```nginx
server {
    listen 443 ssl http2;
    server_name yourdomain.com;

    ssl_certificate /etc/nginx/ssl/fullchain.pem;
    ssl_certificate_key /etc/nginx/ssl/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256;

    location / {
        proxy_pass http://frontend:80;
    }

    location /api {
        proxy_pass http://backend:8080;
        proxy_http_version 1.1;
    }

    location /api/ws {
        proxy_pass http://backend:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

### Database Setup

```sql
-- Create production database
CREATE USER truholdem_prod WITH PASSWORD 'secure_password';
CREATE DATABASE truholdem_prod OWNER truholdem_prod;
GRANT ALL PRIVILEGES ON DATABASE truholdem_prod TO truholdem_prod;
```

Liquibase handles schema migrations automatically on startup.

---

## CI/CD Pipeline

GitHub Actions workflow (`.github/workflows/ci-cd.yml`):

### Pipeline Stages

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Backend Tests  │     │ Frontend Tests  │     │    Lint         │
│  (PostgreSQL)   │     │   (Jest)        │     │   (ESLint)      │
└────────┬────────┘     └────────┬────────┘     └────────┬────────┘
         │                       │                       │
         └───────────────────────┼───────────────────────┘
                                 │
                         ┌───────▼───────┐
                         │   E2E Tests   │
                         │   (Cypress)   │
                         └───────┬───────┘
                                 │
                         ┌───────▼───────┐
                         │ Docker Build  │
                         │  & Push GHCR  │
                         └───────────────┘
```

### Triggers

- **Push to main/develop**: Full pipeline with Docker build
- **Pull requests**: Tests only (no Docker build)

### Test Environment

The CI pipeline provisions:

- PostgreSQL 16 service container
- Node.js 20 for frontend builds
- Java 21 (Temurin) for backend
- Cypress for E2E tests

---

## Monitoring & Observability

### Prometheus Metrics

Backend exposes metrics at `/api/actuator/prometheus`:

```
# Custom game metrics
truholdem_games_active_total
truholdem_games_created_total
truholdem_hands_played_total
truholdem_player_actions_total{action="fold|call|raise"}

# JVM metrics
jvm_memory_used_bytes
jvm_gc_pause_seconds

# HTTP metrics
http_server_requests_seconds_count
http_server_requests_seconds_sum
```

### Grafana Dashboards

Pre-configured dashboards in `docker/grafana/dashboards/`:

- **Application Overview**: Request rates, latencies, error rates
- **JVM Metrics**: Memory, GC, threads
- **Game Statistics**: Active games, hands per minute
- **Database**: Connection pool, query times

### Distributed Tracing (Jaeger)

OpenTelemetry automatically traces:

- HTTP requests
- Database queries
- Redis operations
- WebSocket events

Access traces at http://localhost:16686

### Alert Rules

Example Prometheus alerts (`docker/prometheus/alerts.yml`):

```yaml
groups:
  - name: truholdem
    rules:
      - alert: HighErrorRate
        expr: rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 0.1
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: High error rate detected

      - alert: SlowResponses
        expr: histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) > 2
        for: 10m
        labels:
          severity: warning

      - alert: DatabaseConnectionsExhausted
        expr: hikaricp_connections_active / hikaricp_connections_max > 0.9
        for: 5m
        labels:
          severity: critical
```

---

## Scaling

### Horizontal Scaling

Enable WebSocket clustering for multiple backend instances:

```yaml
backend:
  deploy:
    replicas: 3
  environment:
    WEBSOCKET_CLUSTER_ENABLED: "true"
    REDIS_HOST: redis
```

Architecture with load balancer:

```
                    ┌─────────────┐
                    │   Nginx     │
                    │   (LB)      │
                    └──────┬──────┘
           ┌───────────────┼───────────────┐
           │               │               │
    ┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐
    │  Backend 1  │ │  Backend 2  │ │  Backend 3  │
    └──────┬──────┘ └──────┬──────┘ └──────┬──────┘
           │               │               │
           └───────────────┼───────────────┘
                    ┌──────▼──────┐
                    │    Redis    │
                    │  (Pub/Sub)  │
                    └─────────────┘
```

### Load Balancer Configuration

```nginx
upstream backend {
    least_conn;
    server backend1:8080;
    server backend2:8080;
    server backend3:8080;
}

# Sticky sessions for WebSocket
upstream backend_ws {
    ip_hash;
    server backend1:8080;
    server backend2:8080;
    server backend3:8080;
}
```

See `docs/WEBSOCKET-CLUSTER-DEPLOYMENT.md` for detailed cluster setup.

---

## Troubleshooting

### Common Issues

**Backend won't start**
```bash
# Check logs
docker-compose logs backend

# Common fixes
# 1. Wait for PostgreSQL to be ready
# 2. Verify database credentials
# 3. Check JWT_SECRET is set (min 32 chars)
```

**WebSocket connection fails**
```bash
# Verify CORS settings
curl -v http://localhost:8080/api/ws/info

# Check WebSocket origins config
echo $WEBSOCKET_ORIGINS
```

**Database connection pool exhausted**
```bash
# Check active connections
docker-compose exec postgres psql -U user -d truholdem \
  -c "SELECT count(*) FROM pg_stat_activity WHERE datname='truholdem';"

# Increase pool size in application.properties
spring.datasource.hikari.maximum-pool-size=30
```

**Out of memory errors**
```bash
# Increase JVM heap
docker-compose up -d backend -e JAVA_OPTS="-Xmx1024m -Xms512m"
```

### Health Check Endpoints

| Endpoint | Description |
|----------|-------------|
| `/api/actuator/health` | Overall health status |
| `/api/actuator/health/db` | Database connectivity |
| `/api/actuator/health/redis` | Redis connectivity |
| `/api/actuator/health/diskSpace` | Disk space |
| `/api/actuator/metrics` | All metrics |
| `/api/actuator/prometheus` | Prometheus format |

### Useful Commands

```bash
# View all logs
docker-compose logs -f

# Restart specific service
docker-compose restart backend

# Execute command in container
docker-compose exec backend sh

# Database backup
docker-compose exec postgres pg_dump -U user truholdem > backup.sql

# Database restore
cat backup.sql | docker-compose exec -T postgres psql -U user truholdem

# Clean up everything
docker-compose down -v --rmi all
```

---

## Additional Resources

- [Architecture Documentation](./ARCHITECTURE.md)
- [WebSocket Cluster Setup](./WEBSOCKET-CLUSTER-DEPLOYMENT.md)
- [API Reference](http://localhost:8080/api/swagger-ui.html)
