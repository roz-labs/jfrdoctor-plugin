#!/usr/bin/env bash
# Fails if the project's three version declarations have drifted apart. They are
# meant to move together on every release:
#
#   pom.xml                              /project/version
#   .claude-plugin/plugin.json           "version"
#   src/.../mcp/McpServer.java           SERVER_VERSION  (what the MCP server
#                                        reports to clients in initialize)
#
# The Java constant was previously unchecked, which meant a release could bump
# the two manifests and silently keep announcing the old version over the wire.
#
# Uses python3 (not xmllint/jq): xmllint isn't preinstalled on GitHub's
# ubuntu-latest runners (confirmed the hard way — a first version of this
# script using it failed CI with "xmllint: command not found" even though
# it worked in local dev). python3 ships everywhere this script needs to
# run, dev machine or CI, with no extra install step.
set -euo pipefail

command -v python3 >/dev/null 2>&1 || {
  echo "ERROR: python3 not found on PATH — required by this script." >&2
  exit 1
}

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

python3 - \
  "$REPO_ROOT/pom.xml" \
  "$REPO_ROOT/.claude-plugin/plugin.json" \
  "$REPO_ROOT/src/main/java/jfrdoc/mcp/McpServer.java" <<'EOF'
import json
import re
import sys
import xml.etree.ElementTree as ET

pom_path, plugin_path, server_path = sys.argv[1], sys.argv[2], sys.argv[3]

ns = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ET.parse(pom_path).getroot()
version_el = root.find("m:version", ns)
if version_el is None or not version_el.text:
    sys.exit(f"ERROR: could not find /project/version in {pom_path}")
pom_version = version_el.text.strip()

with open(plugin_path) as f:
    plugin_version = json.load(f)["version"]

with open(server_path) as f:
    match = re.search(r'SERVER_VERSION\s*=\s*"([^"]+)"', f.read())
if match is None:
    sys.exit(f"ERROR: could not find SERVER_VERSION in {server_path}")
server_version = match.group(1)

versions = {
    "pom.xml": pom_version,
    "plugin.json": plugin_version,
    "McpServer.java": server_version,
}

if len(set(versions.values())) != 1:
    detail = ", ".join(f"{name} is {value}" for name, value in versions.items())
    sys.exit(f"ERROR: version mismatch — {detail}.")

print(f"OK: pom.xml, plugin.json and McpServer.java all at {pom_version}.")
EOF
