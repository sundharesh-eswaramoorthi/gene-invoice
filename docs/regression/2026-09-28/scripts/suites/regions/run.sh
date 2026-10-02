#!/usr/bin/env bash
# run.sh <round> [index=1] : fresh env -> suite -> always tear down.
set -uo pipefail
E2E="$(cd "$(dirname "$0")/../.." && pwd)"
ROUND="${1:-1}"; IDX="${2:-1}"
trap '"$E2E/env/down.sh" regions >/dev/null 2>&1 || true' EXIT
"$E2E/env/up.sh" regions "$IDX" --fresh || exit 3
source "$E2E/runtime/regions/env.sh"
E2E_ROUND="$ROUND" node "$(dirname "$0")/run.js" "$ROUND"
