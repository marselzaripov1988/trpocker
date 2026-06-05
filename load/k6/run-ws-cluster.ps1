<#
.SYNOPSIS
  WebSocket capacity scenario (cluster x N WS clients) — PowerShell runner.

.DESCRIPTION
  Boots the round-robin cluster, opens a fleet of STOMP-over-WebSocket subscribers
  (load/k6/websocket-cluster.js) through the LB, then reports how the connections spread across the
  nodes (per-node websocket_sessions_local gauge) and each node's heap.

.EXAMPLE
  load\k6\run-ws-cluster.ps1
  $env:CONNECTIONS=2000; $env:HOLD='300s'; load\k6\run-ws-cluster.ps1
  $env:SEED='1'; $env:CONNECTIONS=2000; load\k6\run-ws-cluster.ps1
  $env:KEEP='1'; load\k6\run-ws-cluster.ps1

  Pushing toward 10000 is OS-bound (file descriptors / ephemeral ports), not app-bound — run on an
  adequately sized host. The 2-node round-robin stack splits the fleet ~50/50.

  Requires: docker, k6, curl.
#>
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..\..')

$CONNECTIONS = if ($env:CONNECTIONS) { $env:CONNECTIONS } else { '500' }
$RAMP = if ($env:RAMP) { $env:RAMP } else { '60s' }
$HOLD = if ($env:HOLD) { $env:HOLD } else { '120s' }
$SEED = if ($env:SEED) { $env:SEED } else { '0' }
$KEEP = if ($env:KEEP) { $env:KEEP } else { '0' }
$LB = 'http://localhost:8092'
$Compose = @('compose', '-f', 'docker-compose.scale.yml')

function Wait-Health($url) {
  Write-Host "  waiting for $url ..."
  while ($true) {
    try { if ((Invoke-WebRequest -UseBasicParsing -Uri $url).StatusCode -eq 200) { break } } catch {}
    Start-Sleep -Seconds 3
  }
}

function Report-Node($name, $url) {
  $prom = ''
  try { $prom = (Invoke-WebRequest -UseBasicParsing -Uri "$url/api/actuator/prometheus").Content } catch {}
  $sessions = ($prom -split "`n" | Where-Object { $_ -match '^websocket_sessions_local' } |
    ForEach-Object { [double]($_ -split '\s+')[-1] } | Measure-Object -Sum).Sum
  $heapBytes = ($prom -split "`n" | Where-Object { $_ -match '^jvm_memory_used_bytes\{area="heap"' } |
    ForEach-Object { [double]($_ -split '\s+')[-1] } | Measure-Object -Sum).Sum
  $heap = if ($heapBytes) { [math]::Round($heapBytes / 1MB) } else { '?' }
  "{0,-8} ws_sessions={1,-7} heap={2}MB" -f $name, [int]$sessions, $heap | Write-Host
}

try {
  Write-Host '=== building cluster image (if missing) ==='
  docker image inspect truholdem-cluster-backend:latest 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) { docker compose -f docker-compose.cluster.yml build }

  Write-Host '=== booting 2-node round-robin cluster ==='
  docker @Compose up -d backend1 backend2 nginx
  Wait-Health 'http://localhost:8081/api/actuator/health'
  Wait-Health 'http://localhost:8082/api/actuator/health'
  Wait-Health "$LB/api/actuator/health"

  $tournamentEnv = @()
  if ($SEED -eq '1') {
    Write-Host '=== seeding a bot tournament for real broadcast traffic ==='
    $body = '{"name":"WS Load","type":"SIT_AND_GO","startingChips":1500,"minPlayers":2,"maxPlayers":1000,"buyIn":0,"blindStructureType":"TURBO"}'
    $tid = $null
    try {
      $resp = Invoke-RestMethod -Method Post -Uri "$LB/api/v1/tournaments" -ContentType 'application/json' -Body $body
      $tid = $resp.id
    } catch {}
    if ($tid) {
      try { Invoke-RestMethod -Method Post -Uri "$LB/api/v1/admin/tournaments/$tid/register-bots" -ContentType 'application/json' -Body '{"count":900,"namePrefix":"Bot_"}' | Out-Null } catch {}
      try { Invoke-RestMethod -Method Post -Uri "$LB/api/v1/admin/tournaments/$tid/start" | Out-Null } catch {}
      Write-Host "  tournament $tid seeded + started"
      $tournamentEnv = @('-e', "TOURNAMENT_ID=$tid")
    } else {
      Write-Host '  WARN: could not seed tournament — running connection-only (no broadcasts)'
    }
  }

  Write-Host "=== running k6: $CONNECTIONS connections (ramp $RAMP, hold $HOLD) via $LB ==="
  & k6 run -e BASE_URL="$LB" -e CONNECTIONS="$CONNECTIONS" -e RAMP="$RAMP" -e HOLD="$HOLD" `
    @tournamentEnv load/k6/websocket-cluster.js

  Write-Host ''
  Write-Host '=== per-node distribution (at end of run) ==='
  Report-Node 'node-1' 'http://localhost:8081'
  Report-Node 'node-2' 'http://localhost:8082'
} finally {
  if ($KEEP -eq '1') {
    Write-Host "KEEP=1 — leaving the stack up ($LB). Tear down with: docker compose -f docker-compose.scale.yml down -v"
  } else {
    Write-Host '=== tearing down ==='
    docker @Compose down -v 2>$null | Out-Null
  }
}
