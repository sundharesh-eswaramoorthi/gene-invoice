#!/usr/bin/env bash
# run.sh [round] : fresh env (area regression, index 6) -> suite -> always down.
set -uo pipefail
E2E="$(cd "$(dirname "$0")/../.." && pwd)"
ROUND="${1:-${E2E_ROUND:-1}}"
trap '"$E2E/env/down.sh" regression' EXIT
"$E2E/env/up.sh" regression 6 --fresh || exit 1
source "$E2E/runtime/regression/env.sh"
E2E_ROUND="$ROUND" node "$E2E/suites/regression/run.js" "$ROUND"
