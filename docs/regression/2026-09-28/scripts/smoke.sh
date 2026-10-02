#!/usr/bin/env bash
# Harness smoke: fresh env "smoke" on index 0 -> smoke.js -> down.
set -uo pipefail
E="$(cd "$(dirname "$0")" && pwd)"
"$E/env/up.sh" smoke 0 --fresh || exit 1
source "$E/runtime/smoke/env.sh"
(cd "$E" && node smoke.js); RC=$?
[[ "${KEEP:-0}" == 1 ]] || "$E/env/down.sh" smoke
exit $RC
