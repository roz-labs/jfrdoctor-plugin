#!/usr/bin/env bash
# Smoke test for the `diff` CLI subcommand (jfr_baseline_diff's headless
# entry point) — the piece the composite action (action.yml) drives in CI.
# Unlike test/smoke.sh this never speaks MCP; it asserts on the plain
# stdout/exit-code contract a shell script or CI Action depends on.
# Requires lib/jfrdoc-mcp.jar (./build.sh) and samples/sample.jfr
# (./samples/gen-sample.sh).
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=lib/jfrdoc-mcp.jar
SAMPLE=samples/sample.jfr
[ -f "$JAR" ] || { echo "missing $JAR — run ./build.sh"; exit 1; }
[ -f "$SAMPLE" ] || { echo "missing $SAMPLE — run ./samples/gen-sample.sh"; exit 1; }

failures=0
check() {
  if [ "$1" -eq "$2" ]; then
    echo "PASS  $3 (exit $2)"
  else
    echo "FAIL  $3 (expected exit $1, got $2)"
    failures=$((failures + 1))
  fi
}

echo "--- self-compare: a recording against itself must PASS ---"
set +e
java -jar "$JAR" diff --baseline "$SAMPLE" --current "$SAMPLE" --container-memory-mb 1000 > /tmp/jfrdoc-diff-self.json
status=$?
set -e
check 0 "$status" "self-compare exits 0"
grep -q '"verdict": "PASS"' /tmp/jfrdoc-diff-self.json \
  && echo "PASS  self-compare JSON reports verdict PASS" \
  || { echo "FAIL  self-compare JSON reports verdict PASS"; failures=$((failures + 1)); }

echo "--- forced regression via a negative threshold must FAIL ---"
set +e
java -jar "$JAR" diff --baseline "$SAMPLE" --current "$SAMPLE" --allocation-threshold-pct -1 \
  > /tmp/jfrdoc-diff-forced.json 2>/dev/null
status=$?
set -e
check 1 "$status" "forced regression exits 1"
grep -q '"verdict": "FAIL"' /tmp/jfrdoc-diff-forced.json \
  && echo "PASS  forced-regression JSON reports verdict FAIL" \
  || { echo "FAIL  forced-regression JSON reports verdict FAIL"; failures=$((failures + 1)); }

echo "--- missing --current is a usage error, not a crash ---"
set +e
java -jar "$JAR" diff --baseline "$SAMPLE" > /dev/null 2>&1
status=$?
set -e
check 2 "$status" "usage error exits 2"

echo "--- nonexistent baseline file is a clean input error ---"
set +e
java -jar "$JAR" diff --baseline /no/such/file.jfr --current "$SAMPLE" > /dev/null 2>&1
status=$?
set -e
check 2 "$status" "missing baseline file exits 2"

if [ "$failures" -gt 0 ]; then
  echo
  echo "$failures check(s) failed"
  exit 1
fi
echo
echo "All diff-cli-smoke checks passed."
