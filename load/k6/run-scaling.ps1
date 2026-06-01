# Scaling benchmark: run the action-path load test against 1 node, then 2 nodes behind a round-robin LB.
# Uses docker-compose.scale.yml (auth + rate limiting disabled, Hibernate schema, Phase 5 features on).
#
# Usage (from repo root):
#   .\load\k6\run-scaling.ps1
#   $env:VUS=50; $env:DURATION='60s'; .\load\k6\run-scaling.ps1
#   $env:KEEP='1'; .\load\k6\run-scaling.ps1     # leave the stack up afterwards
#
# Requires: docker, k6 (https://grafana.com/docs/k6/latest/set-up/install-k6/).
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..\..')

$VUS = if ($env:VUS) { $env:VUS } else { '30' }
$DURATION = if ($env:DURATION) { $env:DURATION } else { '40s' }
$RAMP = if ($env:RAMP) { $env:RAMP } else { '10s' }
$ROUNDS = if ($env:ACTION_ROUNDS) { $env:ACTION_ROUNDS } else { '60' }
$compose = @('compose', '-f', 'docker-compose.scale.yml')
$aJson = 'load/k6/.scale-1node.json'
$bJson = 'load/k6/.scale-2node.json'

function Wait-Health($url) {
  Write-Host "  waiting for $url ..."
  while ($true) {
    try { if ((Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 5).StatusCode -eq 200) { break } } catch {}
    Start-Sleep -Seconds 3
  }
}

function Run-K6($baseUrl, $out) {
  k6 run --quiet `
    -e BASE_URL=$baseUrl -e VUS=$VUS -e RAMP=$RAMP -e DURATION=$DURATION -e ACTION_ROUNDS=$ROUNDS `
    --summary-export=$out load/k6/game-actions.js
}

Write-Host "=== Phase A: single node (http://localhost:8081) ==="
& docker @compose up -d backend1
Wait-Health 'http://localhost:8081/api/actuator/health'
Run-K6 'http://localhost:8081' $aJson

Write-Host "=== Phase B: two nodes via round-robin LB (http://localhost:8092) ==="
& docker @compose up -d backend2 nginx
Wait-Health 'http://localhost:8082/api/actuator/health'
Wait-Health 'http://localhost:8092/api/actuator/health'
Run-K6 'http://localhost:8092' $bJson

Write-Host ""
Write-Host "=== Comparison (VUS=$VUS, DURATION=$DURATION) ==="
foreach ($pair in @(@('1 node ', $aJson), @('2 nodes', $bJson))) {
  $m = (Get-Content $pair[1] -Raw | ConvertFrom-Json).metrics
  $p95 = if ($m.'http_req_duration{name:game_action}') { $m.'http_req_duration{name:game_action}'.values.'p(95)' } else { $m.http_req_duration.values.'p(95)' }
  $reqs = [math]::Round($m.http_reqs.rate, 1)
  $err = [math]::Round($m.http_req_failed.value * 100, 1)
  Write-Host ("{0}  reqs/s={1}  action_p95={2}ms  errors={3}%  games={4}" -f $pair[0], $reqs, [math]::Round($p95), $err, $m.hands_started.count)
}

if ($env:KEEP -eq '1') {
  Write-Host "Stack left running (KEEP=1). Tear down with: docker compose -f docker-compose.scale.yml down -v"
} else {
  & docker @compose down -v
}
