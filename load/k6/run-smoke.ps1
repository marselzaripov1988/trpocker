# Run smoke tournament-table k6 test against local backend.
$ErrorActionPreference = "Stop"
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Set-Location $root
k6 run load/k6/tournament-table.js @args
