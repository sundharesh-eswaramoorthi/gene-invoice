#!/usr/bin/env bash
# restart-backend.sh <area> kill|start|restart
#   kill    : kill -9 this area's backend (only the PID recorded in runtime/<area>/pids, checked against its
#             command line), leaving the database and the web server alone.
#   start   : start the backend again exactly as env/up.sh does (same port, same database, NOT dropped),
#             record the new PID in runtime/<area>/pids and wait until login answers.
#   restart : kill, then start.
set -euo pipefail
E2E_ENV="$(cd "$(dirname "$0")/../../env" && pwd)"
source "$E2E_ENV/common.sh"
AREA="${1:?usage: restart-backend.sh <area> kill|start|restart}"; ACTION="${2:-restart}"
e2e_check_area "$AREA"
RT="$E2E_HOME/runtime/$AREA"; PIDS="$RT/pids"; JAR="$E2E_HOME/dist/backend.jar"
[[ -f "$PIDS" ]] || { echo "no pids file for $AREA" >&2; exit 1; }
API_PORT=$(awk '$1=="api_port"{print $2}' "$PIDS"); DB=$(awk '$1=="db"{print $2}' "$PIDS")
[[ "$DB" =~ ^e2e_ ]] || { echo "refusing: db $DB" >&2; exit 1; }
(( API_PORT >= 18100 && API_PORT <= 18199 )) || { echo "refusing: port $API_PORT" >&2; exit 1; }

do_kill() {
  local BPID; BPID=$(awk '$1=="backend"{print $2}' "$PIDS")
  if e2e_pid_is "$BPID" "$JAR"; then
    kill -9 "$BPID"
    for i in $(seq 1 40); do kill -0 "$BPID" 2>/dev/null || break; sleep 0.25; done
    echo "[restart] killed backend pid $BPID"
  else
    echo "[restart] backend pid $BPID not running (or not ours)"
  fi
}
do_start() {
  if e2e_port_busy "$API_PORT"; then echo "[restart] port $API_PORT busy" >&2; exit 1; fi
  (
    export SPRING_PROFILES_ACTIVE=dev DB_URL="jdbc:postgresql://localhost:$E2E_PG_PORT/$DB" DB_USER=e2e DB_PASSWORD=e2e \
           SERVER_PORT="$API_PORT" SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=5 MAIL_TRANSPORT=none \
           DOCUMENT_ROOT="$RT/documents"
    nohup java -Xmx512m -De2e.area="$AREA" -jar "$JAR" >> "$RT/backend.log" 2>&1 < /dev/null &
    NEW=$!
    grep -v '^backend ' "$PIDS" > "$PIDS.tmp"; echo "backend $NEW" >> "$PIDS.tmp"; mv "$PIDS.tmp" "$PIDS"
  )
  local BPID; BPID=$(awk '$1=="backend"{print $2}' "$PIDS")
  echo "[restart] started backend pid $BPID"
  for i in $(seq 1 120); do
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 5 -H 'Content-Type: application/json' \
      -d '{"username":"admin","password":"admin123"}' "http://localhost:$API_PORT/api/auth/login" 2>/dev/null || true)
    [[ "$code" == 200 ]] && { echo "[restart] backend answering"; return 0; }
    kill -0 "$BPID" 2>/dev/null || { echo "[restart] backend exited" >&2; tail -30 "$RT/backend.log" >&2; exit 1; }
    sleep 1
  done
  echo "[restart] backend not answering" >&2; exit 1
}
case "$ACTION" in
  kill) do_kill ;;
  start) do_start ;;
  restart) do_kill; do_start ;;
  *) echo "unknown action $ACTION" >&2; exit 2 ;;
esac
