#!/usr/bin/env bash
# Fails if the project's two version declarations have drifted apart. They are
# meant to move together on every release:
#
#   .claude-plugin/plugin.json           "version"
#   src/.../mcp/McpServer.java           SERVER_VERSION  (what the MCP server
#                                        reports to clients in initialize)
#
# The Java constant was previously unchecked, which meant a release could bump
# the manifest and silently keep announcing the old version over the wire.
#
# Uses python3 (not jq): python3 ships everywhere this script needs to run,
# dev machine or CI, with no extra install step.
set -euo pipefail

command -v python3 >/dev/null 2>&1 || {
  echo "ERROR: python3 not found on PATH — required by this script." >&2
  exit 1
}

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

python3 - \
  "$REPO_ROOT/.claude-plugin/plugin.json" \
  "$REPO_ROOT/src/main/java/jfrdoc/mcp/McpServer.java" <<'EOF'
import json
import re
import sys

plugin_path, server_path = sys.argv[1], sys.argv[2]

with open(plugin_path) as f:
    plugin_version = json.load(f)["version"]

with open(server_path) as f:
    match = re.search(r'SERVER_VERSION\s*=\s*"([^"]+)"', f.read())
if match is None:
    sys.exit(f"ERROR: could not find SERVER_VERSION in {server_path}")
server_version = match.group(1)

if plugin_version != server_version:
    sys.exit(f"ERROR: version mismatch — plugin.json is {plugin_version}, "
             f"McpServer.java is {server_version}.")

print(f"OK: plugin.json and McpServer.java both at {plugin_version}.")
EOF
