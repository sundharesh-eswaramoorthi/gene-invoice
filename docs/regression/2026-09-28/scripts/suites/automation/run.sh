#!/usr/bin/env bash
# run.sh [round]  : up (fresh) -> suite -> down (always). Results: runs/r<round>/automation.json
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; E2E="$(cd "$HERE/../.." && pwd)"
ROUND="${1:-${E2E_ROUND:-1}}"
trap '"$E2E/env/down.sh" automation' EXIT
"$E2E/env/up.sh" automation 5 --fresh || exit 1
source "$E2E/runtime/automation/env.sh"
node "$HERE/run.js" "$ROUND" 2>&1 | tee "$HERE/run-r$ROUND.log"
