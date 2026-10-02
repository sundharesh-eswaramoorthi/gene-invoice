#!/usr/bin/env bash
# up.sh <area> <index> [--fresh]
#   Brings up an isolated environment for one test area:
#     Postgres database e2e_<area> in container gi-e2e-db (host port 18432)
#     backend  http://localhost:<18100+index>   (dist/backend.jar, profile dev, mail transport none)
#     web      http://localhost:<18200+index>/  (dist/web with its API URL rewritten to the backend above)
#   Idempotent: if the area is already up on the same index and answering, it just prints the URLs.
#   --fresh (or E2E_FRESH=1) always tears the area down first (new empty database, new processes).
#   Writes runtime/<area>/env.sh (source it) and runtime/<area>/env.json.
set -euo pipefail
source "$(dirname "$0")/common.sh"
AREA="${1:?usage: up.sh <area> <index 0-99> [--fresh]}"; IDX="${2:?usage: up.sh <area> <index 0-99> [--fresh]}"
FRESH="${E2E_FRESH:-0}"; [[ "${3:-}" == "--fresh" ]] && FRESH=1
e2e_check_area "$AREA"
[[ "$IDX" =~ ^[0-9]{1,2}$ ]] && (( IDX >= 0 && IDX <= 98 )) || { echo "index must be 0..98 (18199 is the baked placeholder port)" >&2; exit 2; }
API_PORT=$((18100 + IDX)); WEB_PORT=$((18200 + IDX)); DB="e2e_${AREA}"
RT="$E2E_HOME/runtime/$AREA"; PIDS="$RT/pids"
API="http://localhost:$API_PORT"; WEB="http://localhost:$WEB_PORT/"
JAR="$E2E_HOME/dist/backend.jar"; DIST_WEB="$E2E_HOME/dist/web"
[[ -f "$JAR" && -d "$DIST_WEB" ]] || { echo "dist missing: run $E2E_HOME/build-dist.sh first" >&2; exit 1; }

login_ok() {
  curl -s -o /dev/null -w '%{http_code}' -m 5 -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin123"}' "$API/api/auth/login" 2>/dev/null | grep -q '^200$'
}

# ---- already up? ------------------------------------------------------------------------
if [[ -f "$PIDS" && "$FRESH" != 1 ]]; then
  BPID=$(awk '$1=="backend"{print $2}' "$PIDS"); WPID=$(awk '$1=="web"{print $2}' "$PIDS")
  OLDIDX=$(awk '$1=="index"{print $2}' "$PIDS")
  if [[ "$OLDIDX" == "$IDX" ]] && e2e_pid_is "$BPID" "$JAR" && e2e_pid_is "$WPID" "serve.js" && login_ok; then
    echo "[up] $AREA already up"; echo "API=$API"; echo "WEB=$WEB"; echo "DB=$DB"; echo "ENV=$RT/env.sh"; exit 0
  fi
fi
if [[ -f "$PIDS" ]]; then
  echo "[up] stopping the previous $AREA environment first"
  E2E_KEEP_DB=1 "$(dirname "$0")/down.sh" "$AREA" >/dev/null
fi

for p in "$API_PORT" "$WEB_PORT"; do
  if e2e_port_busy "$p"; then echo "[up] port $p is already in use by a process this harness did not record; pick another index" >&2; exit 1; fi
done

# ---- database ----------------------------------------------------------------------------
if ! docker ps -a --format '{{.Names}}' | grep -qx "$E2E_CONTAINER"; then
  echo "[up] creating container $E2E_CONTAINER"
  docker run -d --name "$E2E_CONTAINER" -e POSTGRES_USER=e2e -e POSTGRES_PASSWORD=e2e \
    -p "$E2E_PG_PORT:5432" postgres:16 >/dev/null
elif ! docker ps --format '{{.Names}}' | grep -qx "$E2E_CONTAINER"; then
  echo "[up] starting container $E2E_CONTAINER"; docker start "$E2E_CONTAINER" >/dev/null
fi
for i in $(seq 1 60); do
  docker exec "$E2E_CONTAINER" pg_isready -U e2e -d postgres >/dev/null 2>&1 && break; sleep 1
done
# pg_isready can pass during the image's first-boot init restart; confirm with a real query.
for i in $(seq 1 30); do e2e_psql postgres 'select 1' >/dev/null 2>&1 && break; sleep 1; done
e2e_psql postgres "DROP DATABASE IF EXISTS $DB WITH (FORCE)" >/dev/null
e2e_psql postgres "CREATE DATABASE $DB" >/dev/null
echo "[up] database $DB created"

# ---- backend -----------------------------------------------------------------------------
mkdir -p "$RT/documents"; : > "$RT/backend.log"
printf 'index %s\napi_port %s\nweb_port %s\ndb %s\n' "$IDX" "$API_PORT" "$WEB_PORT" "$DB" > "$PIDS"
(
  export SPRING_PROFILES_ACTIVE=dev DB_URL="jdbc:postgresql://localhost:$E2E_PG_PORT/$DB" DB_USER=e2e DB_PASSWORD=e2e \
         SERVER_PORT="$API_PORT" SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=5 MAIL_TRANSPORT=none \
         DOCUMENT_ROOT="$RT/documents"
  nohup java -Xmx512m -De2e.area="$AREA" -jar "$JAR" >> "$RT/backend.log" 2>&1 < /dev/null &
  echo "backend $!" >> "$PIDS"
)
BPID=$(awk '$1=="backend"{print $2}' "$PIDS")
echo "[up] backend pid $BPID on :$API_PORT (log $RT/backend.log)"

# ---- web ---------------------------------------------------------------------------------
rm -rf "$RT/web"; cp -R "$DIST_WEB" "$RT/web"
N=0
while IFS= read -r f; do
  c=$(grep -o 'localhost:18199' "$f" | wc -l | tr -d ' ')
  if [[ "$c" -gt 0 ]]; then perl -pi -e "s/localhost:18199/localhost:$API_PORT/g" "$f"; N=$((N + c)); fi
done < <(grep -rl 'localhost:18199' "$RT/web" --include='*.js' || true)
LEFT=$( (grep -rl "localhost:18199" "$RT/web" --include="*.js" || true) | wc -l | tr -d " ")
if [[ "$N" -le 0 || "$LEFT" -ne 0 ]]; then echo "[up] API URL rewrite failed (replaced $N, files left $LEFT)" >&2; exit 1; fi
echo "[up] web bundle rewritten to $API ($N replacement(s))"
(
  nohup node "$E2E_HOME/env/serve.js" "$RT/web" "$WEB_PORT" > "$RT/web.log" 2>&1 < /dev/null &
  echo "web $!" >> "$PIDS"
)
echo "[up] web pid $(awk '$1=="web"{print $2}' "$PIDS") on :$WEB_PORT"

# ---- wait --------------------------------------------------------------------------------
cat > "$RT/env.sh" <<ENV
export E2E_AREA=$AREA E2E_API=$API E2E_WEB=$WEB E2E_DB=$DB E2E_RUNTIME=$RT
ENV
printf '{"area":"%s","index":%s,"api":"%s","web":"%s","db":"%s","runtime":"%s"}\n' \
  "$AREA" "$IDX" "$API" "$WEB" "$DB" "$RT" > "$RT/env.json"
DEADLINE=$((SECONDS + ${E2E_UP_TIMEOUT:-240}))
until login_ok; do
  if ! kill -0 "$BPID" 2>/dev/null; then echo "[up] backend exited; last log lines:" >&2; tail -40 "$RT/backend.log" >&2; exit 1; fi
  if (( SECONDS > DEADLINE )); then echo "[up] backend not answering after ${E2E_UP_TIMEOUT:-240}s" >&2; tail -40 "$RT/backend.log" >&2; exit 1; fi
  sleep 2
done
curl -s -o /dev/null -m 5 "$WEB" || { echo "[up] web server not answering" >&2; exit 1; }
echo "[up] $AREA ready"
echo "API=$API"; echo "WEB=$WEB"; echo "DB=$DB"; echo "ENV=$RT/env.sh"
