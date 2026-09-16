#!/usr/bin/env bash
# Builds the themed demo data in a scratch database and leaves a backend running on it for review.
# It never touches any other database or port. See README.md for swapping the result in.
#
#   docs/demo-data/run.sh [scratch_db] [port] [run_dir]
#   defaults:              geneinvoice_theme 8092   /tmp/gene-invoice-demo-run
#
# Needs: Docker container gene-invoice-db (Postgres on 5433), backend/target jar, node 18+.

set -euo pipefail

DB=${1:-geneinvoice_theme}
PORT=${2:-8092}
RUN=${3:-/tmp/gene-invoice-demo-run}
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
JAR="$REPO/backend/target/gene-invoice-backend-0.0.1-SNAPSHOT.jar"
LOG="$RUN/backend-$PORT.log"
API="http://localhost:$PORT"

pg() { docker exec -i gene-invoice-db psql -v ON_ERROR_STOP=1 -U geneinvoice "$@"; }
say() { printf '\n== %s\n' "$*"; }

stop_backend() {
  local pid
  pid=$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t || true)
  if [ -n "$pid" ]; then kill "$pid"; sleep 3; fi
}
start_backend() { # extra args are passed to the application
  SPRING_PROFILES_ACTIVE=dev DB_URL="jdbc:postgresql://localhost:5433/$DB" DB_USER=geneinvoice DB_PASSWORD=geneinvoice \
    nohup java -jar "$JAR" --server.port="$PORT" "$@" > "$LOG" 2>&1 &
  for _ in $(seq 1 120); do
    if grep -q 'Started GeneInvoiceApplication' "$LOG" 2>/dev/null; then break; fi
    if grep -q 'APPLICATION FAILED' "$LOG" 2>/dev/null; then tail -40 "$LOG"; exit 1; fi
    sleep 1
  done
  sleep 5 # the seeder and the start-up sweep run just after start-up
}

[ "$DB" = geneinvoice ] && { echo "Refusing to generate into the live database"; exit 1; }
mkdir -p "$RUN"
rm -f "$RUN"/*.csv "$RUN"/*.json "$RUN"/warnings.txt

say "Fresh scratch database $DB and backend on :$PORT (promise sweep held off)"
stop_backend
pg -d postgres -c "DROP DATABASE IF EXISTS $DB;" -c "CREATE DATABASE $DB;"
start_backend --app.promises.sweep-interval-ms=86400000
pg -d "$DB" -At -c "SELECT 'bootstrap users: ' || count(*) FROM users;"

say "Generating through the API"
node "$HERE/generate.js" --api "$API" --out "$RUN"

say "Pass 1: dates, promised dates, invoice numbers, history"
M() { node -e "console.log(require('$RUN/meta.json').$1)"; }
pg -d "$DB" -q <<SQL
DROP SCHEMA IF EXISTS demo_gen CASCADE;
CREATE SCHEMA demo_gen;
CREATE TABLE demo_gen.meta(gen_start timestamptz, gen_end timestamptz, sim_setup timestamptz, sim_end timestamptz, fix_start timestamptz);
INSERT INTO demo_gen.meta VALUES ('$(M genStart)', '$(M genEnd)', '$(M simSetup)', '$(M simEnd)', NULL);
CREATE TABLE demo_gen.win(seq int, sim timestamptz, real_start timestamptz, real_end timestamptz);
CREATE TABLE demo_gen.promise_dates(promise_id bigint PRIMARY KEY, real_date date);
SQL
pg -d "$DB" -c "COPY demo_gen.win FROM STDIN WITH (FORMAT csv, HEADER true)" < "$RUN/windows.csv"
pg -d "$DB" -c "COPY demo_gen.promise_dates FROM STDIN WITH (FORMAT csv, HEADER true)" < "$RUN/promise_dates.csv"
pg -d "$DB" < "$HERE/postprocess.sql"

say "Letting the application judge promises on their real dates"
stop_backend
pg -d "$DB" -q -c "UPDATE demo_gen.meta SET fix_start = now();"
start_backend
grep -E 'Promise sweep' "$LOG" || echo "start-up sweep: nothing to do"
TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | node -e "process.stdin.on('data',d=>console.log(JSON.parse(d).token))")
curl -s -X POST "$API/api/promises/recompute?apply=true" -H "Authorization: Bearer $TOKEN" \
  | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>console.log('recompute applied', JSON.parse(s).length, 'change(s)'))"
curl -s -X POST "$API/api/promises/recompute?apply=false" -H "Authorization: Bearer $TOKEN" \
  | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>console.log('recompute preview afterwards', JSON.parse(s).length, 'change(s) (must be 0)'))"

say "Pass 2: dating the promise decisions"
pg -d "$DB" < "$HERE/postprocess-promises.sql"

say "Notifications older than a week are read"
node "$HERE/mark-read.js" --api "$API" --out "$RUN"

say "Checks"
pg -d "$DB" -P pager=off < "$HERE/verify.sql"

echo
echo "Done. Review on $API (database $DB). Run files: $RUN"
