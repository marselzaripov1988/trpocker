#!/usr/bin/env bash
#
# Scaling benchmark: run the action-path load test against 1 node, then 2 nodes behind a round-robin LB,
# and print a side-by-side comparison. Uses docker-compose.scale.yml (auth + rate limiting disabled,
# Hibernate-managed schema, all Phase 5 cluster features on).
#
# Usage (from repo root):
#   load/k6/run-scaling.sh                 # VUS=30, DURATION=40s
#   VUS=50 DURATION=60s load/k6/run-scaling.sh
#   KEEP=1 load/k6/run-scaling.sh          # leave the stack up afterwards
#
# Requires: docker, k6 (https://grafana.com/docs/k6/latest/set-up/install-k6/).
set -euo pipefail
cd "$(dirname "$0")/../.."

VUS=${VUS:-30}
DURATION=${DURATION:-40s}
RAMP=${RAMP:-10s}
ACTION_ROUNDS=${ACTION_ROUNDS:-60}
COMPOSE="docker compose -f docker-compose.scale.yml"
A_JSON=load/k6/.scale-1node.json
B_JSON=load/k6/.scale-2node.json

wait_health() {
  echo "  waiting for $1 ..."
  until [ "$(curl -s -o /dev/null -w '%{http_code}' "$1")" = "200" ]; do sleep 3; done
}

run_k6() {
  k6 run --quiet \
    -e BASE_URL="$1" -e VUS="$VUS" -e RAMP="$RAMP" -e DURATION="$DURATION" -e ACTION_ROUNDS="$ACTION_ROUNDS" \
    --summary-export="$2" load/k6/game-actions.js
}

echo "=== Phase A: single node (http://localhost:8081) ==="
$COMPOSE up -d backend1
wait_health http://localhost:8081/api/actuator/health
run_k6 http://localhost:8081 "$A_JSON"

echo "=== Phase B: two nodes via round-robin LB (http://localhost:8092) ==="
$COMPOSE up -d backend2 nginx
wait_health http://localhost:8082/api/actuator/health
wait_health http://localhost:8092/api/actuator/health
run_k6 http://localhost:8092 "$B_JSON"

echo ""
echo "=== Comparison (VUS=$VUS, DURATION=$DURATION) ==="
if command -v node >/dev/null 2>&1; then
  node -e "
    for (const [tag,f] of [['1 node ',process.argv[1]],['2 nodes',process.argv[2]]]) {
      const m=require('./'+f).metrics;
      const p95=(m['http_req_duration{name:game_action}']||m.http_req_duration).values?.['p(95)']||0;
      console.log(tag, 'reqs/s='+(m.http_reqs.rate||0).toFixed(1).padStart(6),
        'action_p95='+p95.toFixed(0).padStart(5)+'ms',
        'errors='+((m.http_req_failed.value||0)*100).toFixed(1)+'%',
        'games='+(m.hands_started?.count||0));
    }" "$A_JSON" "$B_JSON"
else
  echo "node not found — inspect $A_JSON and $B_JSON manually."
fi

if [ "${KEEP:-0}" = "1" ]; then
  echo "Stack left running (KEEP=1). Tear down with: $COMPOSE down -v"
else
  $COMPOSE down -v
fi
