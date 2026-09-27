#!/usr/bin/env bash
# Unit tests: compiles src/ together with test/java/ (so tests can reach
# package-private code) and runs each test class. Needs only a JDK 21+.
set -euo pipefail
cd "$(dirname "$0")/.."

CLASSES=$(mktemp -d)
trap 'rm -rf "$CLASSES"' EXIT

find src/main/java test/java -name '*.java' -print0 \
  | xargs -0 javac --release 21 -proc:none -encoding UTF-8 -nowarn -d "$CLASSES"

java -cp "$CLASSES" jfrdoc.json.JsonParserTest
java -cp "$CLASSES" jfrdoc.tools.JvmArgumentsTest
java -cp "$CLASSES" jfrdoc.mcp.McpServerTest
