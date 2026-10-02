#!/bin/sh
# Usage: sdiff.sh <fromSnapshotName> [pathspec] [--stat]  — diff from a snapshot to the CURRENT working tree
# (including untracked files), limited to pathspec (e.g. backend/ or frontend/). Read-only.
set -e
cd "$(git rev-parse --show-toplevel)"
FROM="refs/snapshots/$1"; PS="${2:-.}"; MODE="$3"
IDX=$(mktemp -u /tmp/snapidx.XXXXXX)
GIT_INDEX_FILE=$IDX git read-tree HEAD
GIT_INDEX_FILE=$IDX git add -A
TREE=$(GIT_INDEX_FILE=$IDX git write-tree)
rm -f $IDX
if [ "$MODE" = "--stat" ]; then git diff --stat "$FROM" "$TREE" -- "$PS"; else git diff "$FROM" "$TREE" -- "$PS"; fi
