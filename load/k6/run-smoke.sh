#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
export SETUP_STAGGER_SECONDS="${SETUP_STAGGER_SECONDS:-13}"
args=(run)
if [[ -n "${K6_SUMMARY_EXPORT:-}" ]]; then
  args+=(--summary-export="$K6_SUMMARY_EXPORT")
fi
args+=(load/k6/tournament-table.js "$@")
exec k6 "${args[@]}"
