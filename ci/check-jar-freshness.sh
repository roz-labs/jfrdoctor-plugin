#!/usr/bin/env bash
# Fails if the committed lib/jfrdoc-mcp.jar's own classes/resources differ
# from what `mvn package` produces against the current src/ — catches the
# "edited src/, forgot to run ./build.sh" case before it ships. Only jfrdoc's
# own classes and the frameworks/ resources are compared, not the vendored
# dependency classes bundled by the shade plugin (those are pinned by
# pom.xml's dependency versions and don't reflect local dev staleness).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

COMMITTED="$REPO_ROOT/lib/jfrdoc-mcp.jar"
if [ ! -f "$COMMITTED" ]; then
  echo "ERROR: $COMMITTED does not exist."
  exit 1
fi

mvn -B -q clean package
FRESH="$REPO_ROOT/target/jfrdoc-mcp.jar"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/fresh" "$WORK/committed"
(cd "$WORK/fresh" && unzip -q "$FRESH" 'jfrdoc/*' 'frameworks/*')
(cd "$WORK/committed" && unzip -q "$COMMITTED" 'jfrdoc/*' 'frameworks/*')

if ! diff -rq "$WORK/fresh" "$WORK/committed"; then
  echo
  echo "ERROR: lib/jfrdoc-mcp.jar is stale — its jfrdoc/* classes and/or"
  echo "frameworks/* resources differ from what 'mvn package' produces from"
  echo "the current src/. Run ./build.sh and commit the result."
  exit 1
fi

echo "OK: lib/jfrdoc-mcp.jar matches src/."
