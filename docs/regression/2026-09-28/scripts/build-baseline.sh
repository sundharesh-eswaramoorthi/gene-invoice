#!/usr/bin/env bash
# Builds the pre-build backend into baseline-src/, for the migration suite (it boots this jar against a copy of
# the developer's database, then boots the new jar against the same copy).
#   build-baseline.sh            commit 321150e, the last commit before this work
#   build-baseline.sh <ref>      any other commit
set -euo pipefail
E="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$E/../../../.." && pwd)"
REF="${1:-321150e}"
rm -rf "$E/baseline-src"
mkdir -p "$E/baseline-src"
git -C "$REPO" archive "$REF" backend | tar -x -C "$E/baseline-src"
echo "[build-baseline] backend at $REF: mvn -o -q package -DskipTests"
(cd "$E/baseline-src/backend" && mvn -o -q package -DskipTests)
ls "$E/baseline-src/backend/target/gene-invoice-backend-0.0.1-SNAPSHOT.jar"
echo "[build-baseline] done"
