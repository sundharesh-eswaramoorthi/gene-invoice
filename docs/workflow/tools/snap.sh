#!/bin/sh
# Usage: snap.sh <name>  — records the whole working tree (tracked + untracked, honouring .gitignore)
# as a commit object under refs/snapshots/<name>. Never touches HEAD, the branch, the index or files.
set -e
cd "$(git rev-parse --show-toplevel)"
IDX=$(mktemp -u /tmp/snapidx.XXXXXX)
GIT_INDEX_FILE=$IDX git read-tree HEAD
GIT_INDEX_FILE=$IDX git add -A
TREE=$(GIT_INDEX_FILE=$IDX git write-tree)
rm -f $IDX
C=$(git commit-tree "$TREE" -p HEAD -m "snapshot $1")
git update-ref "refs/snapshots/$1" "$C"
echo "$C"
