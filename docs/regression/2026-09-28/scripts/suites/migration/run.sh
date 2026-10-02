#!/usr/bin/env bash
# run.sh [round]  — brings the migration area up (index 7), runs the suite, and ALWAYS brings everything down:
# the suite's own extra processes (baseline / h2 jars, recorded in runtime/migration/pids-extra), its extra
# databases (e2e_migration_base, e2e_migration_seed, e2e_migration_seedup), then env/down.sh migration.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
E2E="$(cd "$HERE/../.." && pwd)"
ROUND="${1:-${E2E_ROUND:-1}}"
cleanup() {
  (cd "$HERE" && node -e "const I=require('./infra');(async()=>{await I.stopExtras();for(const d of ['e2e_migration_base','e2e_migration_seed','e2e_migration_seedup','e2e_migration_legacy2'])I.dropDb(d);})()" ) || true
  "$E2E/env/down.sh" migration || true
}
trap cleanup EXIT
"$E2E/env/up.sh" migration 7 || exit 1
source "$E2E/runtime/migration/env.sh"
export E2E_ROUND="$ROUND"
node "$HERE/run.js" "$ROUND" 2>&1 | tee "$HERE/run-r$ROUND.log"
