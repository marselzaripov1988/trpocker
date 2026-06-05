#!/usr/bin/env bash
#
# WebSocket capacity scenario: boot the round-robin cluster, open a fleet of STOMP-over-WebSocket
# subscribers (load/k6/websocket-cluster.js) through the LB, then report how the connections spread across
# the nodes (per-node `websocket_sessions_local` gauge) and the heap each node carries. This exercises the
# one dimension a single instance can't: thousands of concurrent live WS sessions.
#
# Usage (from repo root):
#   load/k6/run-ws-cluster.sh                          # CONNECTIONS=500, RAMP=60s, HOLD=120s
#   CONNECTIONS=2000 HOLD=300s load/k6/run-ws-cluster.sh
#   SEED=1 CONNECTIONS=2000 load/k6/run-ws-cluster.sh  # also seed a bot tournament → real broadcasts
#   KEEP=1 load/k6/run-ws-cluster.sh                   # leave the stack up afterwards
#
# Pushing toward 10000 on a laptop is OS-bound (file descriptors / ports), not app-bound — raise
# `ulimit -n` (≥ 65535) and run on adequately-sized hosts. The 2-node round-robin stack splits the fleet
# ~50/50; add nodes (edit docker/nginx/scale.conf + docker-compose.scale.yml) to spread 10000 further.
#
# Requires: docker, k6 (https://grafana.com/docs/k6/latest/set-up/install-k6/), curl.
set -euo pipefail
cd "$(dirname "$0")/../.."

CONNECTIONS=${CONNECTIONS:-500}
RAMP=${RAMP:-60s}
HOLD=${HOLD:-120s}
SEED=${SEED:-0}
KEEP=${KEEP:-0}
LB=http://localhost:8092
COMPOSE="docker compose -f docker-compose.scale.yml"

wait_health() {
  echo "  waiting for $1 ..."
  until [ "$(curl -s -o /dev/null -w '%{http_code}' "$1")" = "200" ]; do sleep 3; done
}

# Per-node live-connection count is sampled below during the run via /proc/net/tcp (ESTABLISHED to :8080 =
# 0x1F90) inside each container — the truthful metric, since the app gauge `websocket_sessions_local` stays 0
# for anonymous topic-only subscribers (it tracks seated players, not bare spectators).

cleanup() {
  if [ "$KEEP" = "1" ]; then
    echo "KEEP=1 — leaving the stack up ($LB). Tear down with: $COMPOSE down -v"
  else
    echo "=== tearing down ==="
    $COMPOSE down -v >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

echo "=== building cluster image (if missing) ==="
docker image inspect truholdem-cluster-backend:latest >/dev/null 2>&1 \
  || docker compose -f docker-compose.cluster.yml build

echo "=== booting 2-node round-robin cluster ==="
$COMPOSE up -d backend1 backend2 nginx
wait_health http://localhost:8081/api/actuator/health
wait_health http://localhost:8082/api/actuator/health
wait_health "$LB/api/actuator/health"

TOURNAMENT_ENV=()
if [ "$SEED" = "1" ]; then
  echo "=== seeding a bot tournament for real broadcast traffic ==="
  TID=$(curl -s -X POST "$LB/api/v1/tournaments" -H 'Content-Type: application/json' \
    -d '{"name":"WS Load","type":"SIT_AND_GO","startingChips":1500,"minPlayers":2,"maxPlayers":1000,"buyIn":0,"blindStructureType":"TURBO"}' \
    | sed -n 's/.*"id":"\([0-9a-f-]\{36\}\)".*/\1/p')
  if [ -n "${TID:-}" ]; then
    curl -s -X POST "$LB/api/v1/admin/tournaments/$TID/register-bots" -H 'Content-Type: application/json' \
      -d '{"count":900,"namePrefix":"Bot_"}' >/dev/null || true
    curl -s -X POST "$LB/api/v1/admin/tournaments/$TID/start" >/dev/null || true
    echo "  tournament $TID seeded + started"
    TOURNAMENT_ENV=(-e "TOURNAMENT_ID=$TID")
  else
    echo "  WARN: could not seed tournament — running connection-only (no broadcasts)"
  fi
fi

# Sample the per-node live-connection split in the background WHILE k6 holds the fleet (k6 closes every
# socket on exit, so a post-run count would read ~0). Record the peak per node.
SAMPLES=$(mktemp)
( for _ in $(seq 1 240); do
    n1=$(docker exec truholdem-scale-node-1 sh -c "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '\$2 ~ /:1F90\$/ && \$4==\"01\"' | wc -l" 2>/dev/null || echo 0)
    n2=$(docker exec truholdem-scale-node-2 sh -c "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '\$2 ~ /:1F90\$/ && \$4==\"01\"' | wc -l" 2>/dev/null || echo 0)
    echo "$n1 $n2" >> "$SAMPLES"; sleep 3
  done ) &
SAMPLER_PID=$!

echo "=== running k6: $CONNECTIONS connections (ramp $RAMP, hold $HOLD) via $LB ==="
k6 run \
  -e BASE_URL="$LB" -e CONNECTIONS="$CONNECTIONS" -e RAMP="$RAMP" -e HOLD="$HOLD" \
  "${TOURNAMENT_ENV[@]}" \
  load/k6/websocket-cluster.js || true

kill "$SAMPLER_PID" 2>/dev/null || true
echo ""
echo "=== per-node distribution (peak live connections sampled during the run) ==="
awk 'BEGIN{m=0} {if($1+$2>m){m=$1+$2; a=$1; b=$2}} END{printf "  node-1 established=%d\n  node-2 established=%d\n  total peak=%d\n", a, b, m}' "$SAMPLES"
docker stats --no-stream --format '  {{.Name}} mem={{.MemUsage}} cpu={{.CPUPerc}}' \
  truholdem-scale-node-1 truholdem-scale-node-2 2>/dev/null || true
rm -f "$SAMPLES"
echo "  (a balanced split confirms the LB spread the fleet; the peak total ≈ connections held)"
