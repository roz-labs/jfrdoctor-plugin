#!/usr/bin/env bash
# Protocol + tool smoke test: starts the server exactly as the plugin
# manifest does (java launcher/Launch.java <plugin-root>), drives it over
# stdio like an MCP client, and asserts on the responses. Requires
# samples/sample.jfr (./samples/gen-sample.sh).
#
# The session mixes well-formed requests with malformed lines: each bad line
# must get its own JSON-RPC error while the session keeps serving, and the
# final ping (id 300) proves the server survived all of them.
set -euo pipefail
cd "$(dirname "$0")/.."

SAMPLE=samples/sample.jfr
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
  # Queued behind the nine calls above, then cancelled: must get no response.
  echo "{\"jsonrpc\":\"2.0\",\"id\":110,\"method\":\"tools/call\",\"params\":{\"name\":\"jfr_summary\",\"arguments\":{\"path\":\"$SAMPLE\"}}}"
  echo '{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":110}}'

  echo '{"jsonrpc":"2.0","id":100,"method":"tools/call","params":{"name":"jfr_summary","arguments":{"path":"/etc/passwd"}}}'
  echo '{"jsonrpc":"2.0","id":101,"method":"tools/call","params":{"name":"no_such_tool","arguments":{}}}'
  echo '{"jsonrpc":"2.0","id":102,"method":"unknown/method"}'

  # --- malformed and hostile input ------------------------------------------
  echo '{"jsonrpc":"2.0","id":200,"method":"ping"'                                  # truncated JSON
  echo '{"jsonrpc":"2.0","id":201,"method":"ping","params":{"x":"\uZZZZ"}}'          # bad escape
  python3 -c 'print("[" * 200000)'                                                   # deep nesting
  echo '{"jsonrpc":"2.0","id":208,"method":"ping","method":"tools/list"}'            # duplicate key
  printf '\xff\xfe\xfd\n'                                                            # invalid UTF-8
  echo '[{"jsonrpc":"2.0","id":202,"method":"ping"}]'                               # batch
  echo '{"jsonrpc":"2.0","id":{"a":1},"method":"ping"}'                             # object id
  python3 -c 'print("{\"x\":\"" + "a" * 1_100_000 + "\"}")'                          # over-long line
  echo '{"jsonrpc":"1.0","id":207,"method":"ping"}'                                 # wrong version
  echo '{"jsonrpc":"2.0","id":203,"method":"tools/call","params":{"name":"jfr_summary","arguments":{"path":123}}}'
  echo '{"jsonrpc":"2.0","id":204,"method":"tools/call","params":{"name":"jfr_gc_stats","arguments":{}}}'
  echo '{"jsonrpc":"2.0","id":205,"method":"tools/call","params":{"name":"jfr_top_methods","arguments":{"path":"x.jfr","top_n":1.5}}}'
  echo '{"jsonrpc":"2.0","id":206,"method":"tools/call","params":{"name":"jfr_allocation","arguments":{"path":"x.jfr","framework":"django"}}}'
  echo '{"jsonrpc":"2.0","id":209,"method":"tools/call","params":{"name":"jfr_summary","arguments":{"path":null}}}'
  echo '{"jsonrpc":"2.0","id":210,"method":"tools/call","params":{"name":"jfr_summary","arguments":[1]}}'
  echo '{"jsonrpc":"2.0","id":211,"method":"tools/call","params":{"name":"jfr_top_methods","arguments":{"path":"x.jfr","top_n":99999999999}}}'
  echo ''                                                                            # blank line: ignored
  echo '{"jsonrpc":"2.0","id":300,"method":"ping"}'
  # EOF here: the server finishes queued tool calls, flushes, and exits.
} | java launcher/Launch.java . 2>/dev/null > "$OUT"

python3 - "$OUT" <<'EOF'
import json, sys

responses, anonymous = {}, []
for line in open(sys.argv[1]):
    r = json.loads(line)
    if r.get("id") is None:
        anonymous.append(r)
    else:
        responses[r["id"]] = r

failures = []
checks = 0
def check(cond, label):
    global checks
    checks += 1
    print(("PASS  " if cond else "FAIL  ") + label)
    if not cond:
        failures.append(label)

init = responses[1]["result"]
check(init["protocolVersion"] == "2025-06-18", "initialize echoes protocol version")
check(init["serverInfo"]["name"] == "jfrdoc", "initialize reports server name")

# The version the server announces must match the one the plugin ships under;
# ci/check-version-sync.sh keeps plugin.json and McpServer.java equal.
with open(".claude-plugin/plugin.json") as f:
    expected_version = json.load(f)["version"]
check(init["serverInfo"]["version"] == expected_version,
      f"initialize reports version {expected_version} "
      f"(got {init['serverInfo']['version']})")
check("tools" in init["capabilities"], "initialize declares tools capability")

tools = responses[2]["result"]["tools"]
check(len(tools) == 9, f"tools/list returns 9 tools (got {len(tools)})")
check(all(t["inputSchema"]["type"] == "object" for t in tools), "every inputSchema is an object schema")
check(all("path" in t["inputSchema"]["properties"] for t in tools), "every tool declares a path property")
check(all(t["annotations"]["readOnlyHint"] is True for t in tools), "every tool is annotated read-only")

check(responses[3]["result"] == {}, "ping returns empty result")

payloads = {}
for rid, name in zip(range(4, 13), [t["name"] for t in tools]):
    res = responses[rid]["result"]
    ok = not res["isError"]
    try:
        payloads[name] = json.loads(res["content"][0]["text"])
    except Exception:
        ok = False
    check(ok, f"tools/call {name} succeeds with JSON payload")

jvm = payloads.get("jfr_summary", {}).get("jvm", {})
check(isinstance(jvm.get("jvmFlags"), list), "jfr_summary reports sanitized jvmFlags")
check("jvmArguments" not in jvm and "javaArguments" not in jvm,
      "jfr_summary never emits raw jvmArguments/javaArguments")

check(110 not in responses, "cancelled tools/call gets no response")
check(responses[100]["result"]["isError"] is True, "non-.jfr path is rejected as tool error")
check(responses[101]["error"]["code"] == -32602, "unknown tool -> invalid params")
check(responses[102]["error"]["code"] == -32601, "unknown method -> method not found")

codes = sorted(r["error"]["code"] for r in anonymous)
check(codes.count(-32700) == 5, f"5 unparseable lines -> 5 parse errors (got {codes.count(-32700)})")
check(codes.count(-32600) == 3, f"batch, object id, over-long line -> 3 invalid requests (got {codes.count(-32600)})")
check(len(anonymous) == 8, f"exactly 8 id-less error responses (got {len(anonymous)})")
check(responses[207]["error"]["code"] == -32600, "jsonrpc 1.0 -> invalid request, id kept")
for rid, label in [(203, "wrong argument type"), (204, "missing required argument"),
                   (205, "non-integer top_n"), (206, "value outside enum"),
                   (209, "explicit null for required argument"), (211, "top_n beyond int range")]:
    res = responses[rid].get("result", {})
    check(res.get("isError") is True, f"{label} -> tool error the model can read")
check(responses[210]["error"]["code"] == -32602, "non-object arguments -> invalid params")
check(responses[300]["result"] == {}, "session still serving after all malformed input")

if failures:
    sys.exit(f"\n{len(failures)} check(s) failed")
print(f"\nAll {checks} checks passed.")
EOF
