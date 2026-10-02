# Shared by up.sh / down.sh / psql.sh. Source it.
E2E_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
E2E_CONTAINER=gi-e2e-db
E2E_PG_PORT=18432
e2e_check_area() {
  [[ "$1" =~ ^[a-z][a-z0-9_]{0,40}$ ]] || { echo "area must match ^[a-z][a-z0-9_]*\$ (got '$1')" >&2; exit 2; }
}
e2e_psql() { # e2e_psql <db> <sql>
  docker exec -i "$E2E_CONTAINER" psql -v ON_ERROR_STOP=1 -U e2e -d "$1" -Atc "$2"
}
# True when <pid> is alive and its command line contains <needle>.
e2e_pid_is() {
  local pid="$1" needle="$2"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null && ps -p "$pid" -o command= | grep -qF -- "$needle"
}
e2e_port_busy() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
