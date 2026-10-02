#!/usr/bin/env bash
# run.sh [round] [index]: fresh env -> suite -> env down (always).
E2E="$(cd "$(dirname "$0")/../.." && pwd)"
ROUND="${1:-${E2E_ROUND:-1}}"; IDX="${2:-3}"
trap '"$E2E/env/down.sh" approvals >/dev/null 2>&1 || true' EXIT
"$E2E/env/up.sh" approvals "$IDX" --fresh || exit 1
source "$E2E/runtime/approvals/env.sh"
node "$(dirname "$0")/run.js" "$ROUND" "${@:3}"
