#!/usr/bin/env bash
# run.sh [round] : fresh env (area tasks, index 4) -> suite -> always down.
set -uo pipefail
E2E="$(cd "$(dirname "$0")/../.." && pwd)"
ROUND="${1:-${E2E_ROUND:-1}}"
trap '"$E2E/env/down.sh" tasks' EXIT
"$E2E/env/up.sh" tasks 4 --fresh || exit 1
source "$E2E/runtime/tasks/env.sh"
E2E_ROUND="$ROUND" node "$E2E/suites/tasks/run.js" "$ROUND"
