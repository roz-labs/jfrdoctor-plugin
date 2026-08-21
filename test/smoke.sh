#!/usr/bin/env bash
# Protocol + tool smoke test: drives the built server over stdio exactly like
# an MCP client and asserts on the responses. Requires lib/jfrdoc-mcp.jar
# (./build.sh) and samples/sample.jfr (./samples/gen-sample.sh).
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=lib/jfrdoc-mcp.jar
SAMPLE=samples/sample.jfr
[ -f "$JAR" ] || { echo "missing $JAR — run ./build.sh"; exit 1; }
[ -f "$SAMPLE" ] || { echo "missing $SAMPLE — run ./samples/gen-sample.sh"; exit 1; }

OUT=$(mktemp)
trap 'rm -f "$OUT"' EXIT

{
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}'
  echo '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
  echo '{"jsonrpc":"2.0","id":3,"method":"ping"}'
  id=4
  for tool in jfr_summary jfr_top_methods jfr_gc_stats jfr_allocation jfr_memory \
              jfr_lock_contention jfr_exceptions jfr_io jfr_native_methods; do
    echo "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"tools/call\",\"params\":{\"name\":\"$tool\",\"arguments\":{\"path\":\"$SAMPLE\"}}}"
    id=$((id+1))
  done
  # jfr_baseline_diff takes baseline_path/current_path instead of path; a
  # recording compared against itself must be a trivial PASS with zero
  # regressions, which is asserted below alongside the other nine tools'
  # generic "succeeds with JSON payload" check.
  echo "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"tools/call\",\"params\":{\"name\":\"jfr_baseline_diff\",\"arguments\":{\"baseline_path\":\"$SAMPLE\",\"current_path\":\"$SAMPLE\",\"container_memory_mb\":1000}}}"
  id=$((id+1))
  echo '{"jsonrpc":"2.0","id":100,"method":"tools/call","params":{"name":"jfr_summary","arguments":{"path":"/etc/passwd"}}}'
  echo '{"jsonrpc":"2.0","id":101,"method":"tools/call","params":{"name":"no_such_tool","arguments":{}}}'
  echo '{"jsonrpc":"2.0","id":102,"method":"unknown/method"}'
  echo "{\"jsonrpc\":\"2.0\",\"id\":103,\"method\":\"tools/call\",\"params\":{\"name\":\"jfr_baseline_diff\",\"arguments\":{\"baseline_path\":\"/etc/passwd\",\"current_path\":\"$SAMPLE\"}}}"
  # The SDK's stdio transport processes requests asynchronously; closing stdin
  # (and thus signalling EOF) immediately after writing can race ahead of
  # in-flight tool calls and drop their responses. A real MCP client (Claude
  # Code) never closes stdin mid-session, so hold the pipe open here too.
  sleep 5
} | java -jar "$JAR" 2>/dev/null > "$OUT"

python3 - "$OUT" <<'EOF'
import json, sys

responses = {}
for line in open(sys.argv[1]):
    r = json.loads(line)
    responses[r["id"]] = r

failures = []
total_checks = 0
def check(cond, label):
    global total_checks
    total_checks += 1
    print(("PASS  " if cond else "FAIL  ") + label)
    if not cond:
        failures.append(label)

init = responses[1]["result"]
check(init["protocolVersion"] == "2025-06-18", "initialize echoes protocol version")
check(init["serverInfo"]["name"] == "jfrdoc", "initialize reports server name")

# The version the server announces must match the one the plugin ships under;
# ci/check-version-sync.sh keeps plugin.json, pom.xml and McpServer.java equal,
# so plugin.json is a fair source of truth for what the wire should say.
with open(".claude-plugin/plugin.json") as f:
    expected_version = json.load(f)["version"]
check(init["serverInfo"]["version"] == expected_version,
      f"initialize reports version {expected_version} "
      f"(got {init['serverInfo']['version']})")
check("tools" in init["capabilities"], "initialize declares tools capability")

tools = responses[2]["result"]["tools"]
check(len(tools) == 10, f"tools/list returns 10 tools (got {len(tools)})")
check(all(t["inputSchema"]["type"] == "object" for t in tools), "every inputSchema is an object schema")

def declares_jfr_path(t):
    props = t["inputSchema"]["properties"]
    return "path" in props or ("baseline_path" in props and "current_path" in props)
check(all(declares_jfr_path(t) for t in tools),
      "every tool declares a path (or baseline_path/current_path) property")

check(responses[3]["result"] == {}, "ping returns empty result")

for rid, name in zip(range(4, 14), [t["name"] for t in tools]):
    res = responses[rid]["result"]
    ok = not res["isError"]
    payload_is_json = True
    try:
        json.loads(res["content"][0]["text"])
    except Exception:
        payload_is_json = False
    check(ok and payload_is_json, f"tools/call {name} succeeds with JSON payload")

diff_result = json.loads(responses[13]["result"]["content"][0]["text"])
check(diff_result["verdict"] == "PASS", "jfr_baseline_diff comparing a recording against itself is a trivial PASS")
check(diff_result["regressions"] == [], "jfr_baseline_diff self-compare reports zero regressions")

check(responses[100]["result"]["isError"] is True, "non-.jfr path is rejected as tool error")
check(responses[101]["error"]["code"] == -32602, "unknown tool -> invalid params")
check(responses[102]["error"]["code"] == -32601, "unknown method -> method not found")
check(responses[103]["result"]["isError"] is True, "jfr_baseline_diff rejects a non-.jfr baseline_path")

if failures:
    sys.exit(f"\n{len(failures)} check(s) failed")
print(f"\nAll {total_checks} checks passed.")
EOF
