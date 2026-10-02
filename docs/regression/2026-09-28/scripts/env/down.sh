#!/usr/bin/env bash
# down.sh <area>
#   Stops only the processes up.sh recorded for this area (checked against their command line, so a
#   recycled PID is never killed) and drops database e2e_<area>. Safe to run repeatedly.
#   E2E_KEEP_DB=1 keeps the database (used by up.sh when restarting).
set -uo pipefail
source "$(dirname "$0")/common.sh"
AREA="${1:?usage: down.sh <area>}"; e2e_check_area "$AREA"
RT="$E2E_HOME/runtime/$AREA"; PIDS="$RT/pids"; DB="e2e_${AREA}"
if [[ -f "$PIDS" ]]; then
  while read -r kind pid; do
    case "$kind" in
      backend) needle="$E2E_HOME/dist/backend.jar" ;;
      web) needle="$E2E_HOME/env/serve.js" ;;
      *) continue ;;
    esac
    if e2e_pid_is "$pid" "$needle"; then
      kill "$pid" 2>/dev/null
      for i in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
      kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
      echo "[down] stopped $kind pid $pid"
    else
      echo "[down] $kind pid $pid not running (or not ours); skipped"
    fi
  done < "$PIDS"
  rm -f "$PIDS"
else
  echo "[down] no recorded processes for $AREA"
fi
if [[ "${E2E_KEEP_DB:-0}" != 1 ]] && docker ps --format '{{.Names}}' | grep -qx "$E2E_CONTAINER"; then
  e2e_psql postgres "DROP DATABASE IF EXISTS $DB WITH (FORCE)" >/dev/null && echo "[down] dropped database $DB"
fi
exit 0
