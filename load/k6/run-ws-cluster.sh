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

# Per-node WebSocket session gauge + heap, scraped straight from each node (bypassing the LB).
report_node() {
  local name=$1 url=$2
  local prom; prom=$(curl -s "$url/api/actuator/prometheus" || true)
  local sessions heap
  sessions=$(echo "$prom" | grep -E '^websocket_sessions_local' | awk -F' ' '{s+=$2} END {printf "%d", s}')
  heap=$(echo "$prom" | grep -E '^jvm_memory_used_bytes\{area="heap"' | awk -F' ' '{s+=$2} END {printf "%.0f", s/1048576}')
  printf "  %-8s ws_sessions=%-7s heap=%sMB\n" "$name" "${sessions:-0}" "${heap:-?}"
}

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

echo "=== running k6: $CONNECTIONS connections (ramp $RAMP, hold $HOLD) via $LB ==="
k6 run \
  -e BASE_URL="$LB" -e CONNECTIONS="$CONNECTIONS" -e RAMP="$RAMP" -e HOLD="$HOLD" \
  "${TOURNAMENT_ENV[@]}" \
  load/k6/websocket-cluster.js || true

echo ""
echo "=== per-node distribution (at end of run) ==="
report_node node-1 http://localhost:8081
report_node node-2 http://localhost:8082
echo "  (a balanced round-robin split confirms the LB spread the fleet; the sum ≈ live connections)"
