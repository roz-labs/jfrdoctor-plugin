#!/usr/bin/env bash
# Builds lib/jfrdoc-mcp.jar (a shaded/uber jar bundling the MCP Java SDK and
# its transitive deps) from src/. Requires JDK 21+ and Maven on PATH.
set -euo pipefail
cd "$(dirname "$0")"

mvn -q -B package

mkdir -p lib
cp target/jfrdoc-mcp.jar lib/jfrdoc-mcp.jar
sha256sum lib/jfrdoc-mcp.jar > lib/jfrdoc-mcp.jar.sha256

echo "Built lib/jfrdoc-mcp.jar"
