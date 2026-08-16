#!/usr/bin/env bash
# Guards against committing a JFR recording that carries private data.
#
# JFR's "profile" settings record three event types that describe the HOST, not
# the recorded workload:
#
#   jdk.InitialEnvironmentVariable  every env var, key AND value
#   jdk.SystemProcess               every process on the machine, full argv
#   jdk.InitialSystemProperty       every -D system property
#
# A recording made on a developer box or in CI therefore contains credentials,
# API tokens, session identifiers and private filesystem paths. This repo shipped
# exactly such a recording once; samples/gen-sample.sh now disables all three, and
# this script is what keeps them off.
#
# Two independent assertions:
#   1. No .jfr file is tracked by git at all. Recordings are generated locally
#      (./samples/gen-sample.sh), never committed.
#   2. Any .jfr present in the working tree contains zero events of those three
#      types — so the recording CI generates and analyses is itself clean.
#
# Uses `jfr`, which ships with the JDK, so there is nothing extra to install.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

PRIVATE_EVENTS=(jdk.InitialEnvironmentVariable jdk.SystemProcess jdk.InitialSystemProperty)
failures=0

# --- 1. no recording may be tracked by git -----------------------------------
tracked=$(git ls-files '*.jfr')
if [ -n "$tracked" ]; then
  echo "ERROR: .jfr recordings are tracked by git:"
  echo "$tracked" | sed 's/^/  /'
  echo
  echo "Recordings capture host environment variables and the full process table."
  echo "Generate them locally with ./samples/gen-sample.sh instead of committing one."
  failures=$((failures + 1))
else
  echo "OK: no .jfr file is tracked by git."
fi

# --- 2. any recording on disk must be free of the private event types --------
command -v jfr >/dev/null 2>&1 || {
  echo "ERROR: 'jfr' not found on PATH — it ships with the JDK and is required here." >&2
  exit 1
}

shopt -s nullglob
recordings=(samples/*.jfr)
shopt -u nullglob

if [ ${#recordings[@]} -eq 0 ]; then
  echo "No recording in samples/ to inspect (run ./samples/gen-sample.sh first)."
else
  for rec in "${recordings[@]}"; do
    summary=$(jfr summary "$rec")
    for event in "${PRIVATE_EVENTS[@]}"; do
      # `jfr summary` prints "<Event Type> <count> <size>"; absent types are omitted.
      count=$(awk -v e="$event" '$1 == e { print $2; found = 1 } END { if (!found) print 0 }' <<<"$summary")
      if [ "$count" -ne 0 ]; then
        echo "ERROR: $rec contains $count $event event(s) — this leaks host data."
        failures=$((failures + 1))
      else
        echo "OK: $rec has no $event events."
      fi
    done
  done
fi

if [ "$failures" -ne 0 ]; then
  echo
  echo "$failures privacy check(s) failed."
  exit 1
fi

echo
echo "All privacy checks passed."
