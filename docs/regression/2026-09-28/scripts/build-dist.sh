#!/usr/bin/env bash
# Builds the backend jar and the Flutter web bundle into e2e/dist.
# The web bundle is baked with API_BASE_URL=http://localhost:18199; env/up.sh rewrites that
# (same length) to the area's own backend port.
#   build-dist.sh            both
#   build-dist.sh backend    jar only
#   build-dist.sh web        web only
set -euo pipefail
E="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
WHAT="${1:-all}"
mkdir -p "$E/dist"

if [[ "$WHAT" == all || "$WHAT" == backend ]]; then
  echo "[build-dist] backend: mvn -o -q package -DskipTests"
  (cd "$REPO/backend" && mvn -o -q package -DskipTests)
  JAR=$(ls -t "$REPO"/backend/target/*.jar | grep -v -- '-plain\|\.original' | head -1)
  cp "$JAR" "$E/dist/backend.jar"
  echo "[build-dist] backend.jar <- $JAR ($(du -h "$E/dist/backend.jar" | cut -f1))"
fi

if [[ "$WHAT" == all || "$WHAT" == web ]]; then
  echo "[build-dist] web: flutter build web --release --dart-define=API_BASE_URL=http://localhost:18199"
  (cd "$REPO/frontend" && flutter build web --release --dart-define=API_BASE_URL=http://localhost:18199)
  rm -rf "$E/dist/web"
  cp -R "$REPO/frontend/build/web" "$E/dist/web"
  N=$(grep -o 'localhost:18199' "$E/dist/web/main.dart.js" | wc -l | tr -d ' ')
  echo "[build-dist] web copied; 'localhost:18199' occurrences in main.dart.js: $N"
  [[ "$N" -gt 0 ]] || { echo "[build-dist] ERROR: API base URL not found in main.dart.js" >&2; exit 1; }
fi
date -u +%FT%TZ > "$E/dist/BUILT_AT"
echo "[build-dist] done"
