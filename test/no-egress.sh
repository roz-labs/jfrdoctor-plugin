#!/usr/bin/env bash
# Checks promises in README.md and SECURITY.md, statically and at the
# system-call level:
# while the server starts, compiles itself and runs all nine tools, it
#   - makes no network connection: no connect/bind/sendto on an IPv4/IPv6
#     socket (the JDK's source launcher creates, and never uses, a few
#     AF_INET sockets while probing the platform — a plain `java Hello.java`
#     does the same, so socket() alone is not counted), and
#   - writes no files: nothing is opened for writing, created, renamed or
#     deleted, apart from /proc/self/coredump_filter, which every JVM opens.
# Requires strace and samples/sample.jfr (./samples/gen-sample.sh).
set -euo pipefail
cd "$(dirname "$0")/.."

# Static half: the plugin's own code uses no environment-variable, process,
# native-library or network API at all.
FORBIDDEN='System\.getenv|ProcessBuilder|Runtime\.getRuntime\(\)\.exec|System\.load|java\.net\.http|URLConnection|new Socket|ServerSocket|DatagramSocket|SocketChannel'
if grep -rn -E "$FORBIDDEN" src/main/java launcher; then
  echo "FAIL  plugin code uses an environment, process, native or network API (above)"
  exit 1
fi
echo "PASS  plugin code uses no environment, process, native or network API"

command -v strace >/dev/null 2>&1 || { echo "FAIL: strace is required (apt-get install strace)"; exit 1; }
[ -f samples/sample.jfr ] || { echo "missing samples/sample.jfr — run ./samples/gen-sample.sh"; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

python3 - > "$WORK/requests" <<'EOF'
import json
print(json.dumps({"jsonrpc": "2.0", "id": 0, "method": "initialize",
                  "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "t", "version": "0"}}}))
for i, t in enumerate(["jfr_summary", "jfr_top_methods", "jfr_gc_stats", "jfr_allocation", "jfr_memory",
                       "jfr_lock_contention", "jfr_exceptions", "jfr_io", "jfr_native_methods"], 1):
    print(json.dumps({"jsonrpc": "2.0", "id": i, "method": "tools/call",
                      "params": {"name": t, "arguments": {"path": "samples/sample.jfr"}}}))
EOF

mapfile -d '' -t SERVER < <(python3 - "$PWD" <<'PY'
import json, sys
server = json.load(open(".claude-plugin/plugin.json"))["mcpServers"]["jfrdoc"]
for part in [server["command"], *server["args"]]:
    sys.stdout.write(part.replace("${CLAUDE_PLUGIN_ROOT}", sys.argv[1]) + "\0")
PY
)

strace -f -qq -o "$WORK/trace" \
  -e trace=socket,connect,bind,sendto,sendmsg,openat,open,creat,mkdir,mkdirat,unlink,unlinkat,rename,renameat,renameat2 \
  "${SERVER[@]}" < "$WORK/requests" > "$WORK/responses" 2>/dev/null

python3 - "$WORK/trace" "$WORK/responses" <<'EOF'
import json, re, sys
trace = open(sys.argv[1]).read().splitlines()
responses = [json.loads(l) for l in open(sys.argv[2])]
failures = []

def check(ok, label, detail=()):
    print(("PASS  " if ok else "FAIL  ") + label)
    for d in list(detail)[:10]:
        print("      " + d)
    if not ok:
        failures.append(label)

ok_calls = [r for r in responses if r.get("id", 0) >= 1 and not r["result"]["isError"]]
check(len(ok_calls) == 9, f"all 9 tools answered successfully under strace (got {len(ok_calls)})")

inet_fds = set()
for line in trace:
    m = re.match(r"(\d+)\s+socket\(AF_INET6?,.*\)\s+=\s+(\d+)", line)
    if m:
        inet_fds.add((m.group(1), m.group(2)))
network = [l for l in trace
           if re.search(r"\b(connect|bind|sendto|sendmsg)\(", l)
           and "AF_UNIX" not in l
           and (re.search(r"AF_INET6?", l) or any(l.startswith(pid) and f"({fd}," in l for pid, fd in inet_fds))]
check(not network, "no connect/bind/send on an IPv4/IPv6 socket", network)

writes = [l for l in trace
          if (re.search(r"O_WRONLY|O_RDWR|O_CREAT", l) or re.search(r"\b(creat|mkdir|mkdirat|unlink|unlinkat|rename|renameat2?)\(", l))
          and "/proc/self/coredump_filter" not in l
          and not re.search(r"= -1 E", l)]
check(not writes, "no file opened for writing, created, renamed or deleted", writes)

if failures:
    sys.exit(f"\n{len(failures)} check(s) failed")
print("\nNo network or file-write activity.")
EOF
