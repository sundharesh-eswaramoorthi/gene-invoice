#!/usr/bin/env bash
# psql.sh <area> "<sql>"   — runs SQL against e2e_<area> only (read the DB to confirm persisted rows, e.g. emails).
set -euo pipefail
source "$(dirname "$0")/common.sh"
AREA="${1:?usage: psql.sh <area> <sql>}"; e2e_check_area "$AREA"
docker exec -i "$E2E_CONTAINER" psql -v ON_ERROR_STOP=1 -U e2e -d "e2e_${AREA}" -P pager=off ${2:+-c "$2"}
