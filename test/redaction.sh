#!/usr/bin/env bash
# End-to-end check of every redaction promise in SECURITY.md, against a real
# recording: test/workload/RedactionWorkload.java runs under JFR with secrets
# in its JVM flags, program arguments and exception messages, reads a file
# under a home directory, and talks to a raw IPv4 address. Every tool's output
# is then searched for those markers, through the server started exactly as
# the plugin manifest starts it. A corrupted copy of the recording checks that
# error responses carry only an exception class name.
#
# The file must live under /home/<user>/ for the home-directory masking to be
# exercised: $HOME is used when it is there (CI runners), otherwise a
# directory is created under /home. If neither is possible the test fails —
# it never silently skips the check.
set -euo pipefail
cd "$(dirname "$0")/.."

WORK=$(mktemp -d)
HOME_BASE=""
cleanup() {
  rm -rf "$WORK"
  [ -n "$HOME_BASE" ] && rm -rf "$HOME_BASE"
}
trap cleanup EXIT

case "${HOME:-}" in
  /home/?*) HOME_BASE="$HOME/.jfrdoc-redaction-test-$$" ;;
  *)
    HOME_BASE="/home/jfrdoc-redaction-user-$$"
    mkdir -p "$HOME_BASE" 2>/dev/null || { echo "FAIL: need a writable directory under /home/ for this test"; exit 1; }
    ;;
esac
HOME_USER=$(echo "$HOME_BASE" | cut -d/ -f3)
DATA_FILE="$HOME_BASE/data/customer-file.bin"
REC="$WORK/redaction.jfr"

javac -d "$WORK/classes" test/workload/RedactionWorkload.java
java -XX:-UsePerfData \
  -Dspring.datasource.password=hunter2 -Dapp.token=tok123secret \
  -XX:HeapDumpPath="$HOME_BASE/dumps" \
  "-XX:StartFlightRecording=filename=$REC,settings=profile,jdk.JavaExceptionThrow#enabled=true,jdk.FileRead#threshold=0ms,jdk.FileWrite#threshold=0ms,jdk.SocketRead#threshold=0ms,jdk.SocketWrite#threshold=0ms,jdk.InitialEnvironmentVariable#enabled=false,jdk.SystemProcess#enabled=false,jdk.InitialSystemProperty#enabled=false" \
  -cp "$WORK/classes" RedactionWorkload "$DATA_FILE" --password=hunter2 >/dev/null 2>&1

# A truncated copy: every tool must fail on it without echoing file content.
head -c 20000 "$REC" > "$WORK/corrupt.jfr"

python3 - "$REC" "$WORK/corrupt.jfr" "$HOME_USER" <<'EOF'
import json, os, re, subprocess, sys

rec, corrupt, home_user = sys.argv[1], sys.argv[2], sys.argv[3]
root = os.getcwd()
server = json.load(open(".claude-plugin/plugin.json"))["mcpServers"]["jfrdoc"]
cmd = [server["command"]] + [a.replace("${CLAUDE_PLUGIN_ROOT}", root) for a in server["args"]]
tools = ["jfr_summary", "jfr_top_methods", "jfr_gc_stats", "jfr_allocation", "jfr_memory",
         "jfr_lock_contention", "jfr_exceptions", "jfr_io", "jfr_native_methods"]

lines = []
for i, t in enumerate(tools):
    lines.append({"jsonrpc": "2.0", "id": i, "method": "tools/call", "params": {"name": t, "arguments": {"path": rec}}})
    lines.append({"jsonrpc": "2.0", "id": 100 + i, "method": "tools/call", "params": {"name": t, "arguments": {"path": corrupt}}})
proc = subprocess.run(cmd, input="".join(json.dumps(l) + "\n" for l in lines), capture_output=True, text=True)
out = {json.loads(l)["id"]: json.loads(l)["result"] for l in proc.stdout.splitlines()}

failures, checks = [], 0
def check(ok, label):
    global checks
    checks += 1
    print(("PASS  " if ok else "FAIL  ") + label)
    if not ok:
        failures.append(label)

texts = {t: out[i]["content"][0]["text"] for i, t in enumerate(tools)}
check(all(not out[i]["isError"] for i in range(len(tools))), "all 9 tools succeed on the workload recording")
everything = "\n".join(texts.values())

for marker, what in [("hunter2", "password from -D flag, program argument and exception message"),
                     ("tok123secret", "token from a -D flag"),
                     ("alice.secret@example.com", "email in an exception message"),
                     ("s3cretpw", "URL userinfo password in an exception message"),
                     ("bob:", "URL userinfo user in an exception message"),
                     ("/home/" + home_user, "home-directory username in file paths and flags"),
                     ("127.0.0.1", "full IPv4 address")]:
    check(marker not in everything, f"no tool output contains the {what}")

exc = json.loads(texts["jfr_exceptions"])
msgs = [c.get("sample_message") for c in exc["top_exception_classes"] if c.get("sample_message")]
check(bool(msgs), "jfr_exceptions reports a sample message")
if msgs:
    m = msgs[0]
    check("[REDACTED-EMAIL]" in m and "password=[REDACTED]" in m and "://[REDACTED]@" in m,
          "sample message shows the email, key=value secret and URL userinfo redacted")
    check(len(m) <= 121, f"sample message truncated to 120 characters plus an ellipsis (got {len(m)})")

io = json.loads(texts["jfr_io"])
paths = [f["path"] for f in io["file_io"]["top_files_by_time"]]
check(any(p.startswith("/home/<redacted>/") and p.endswith("/data/customer-file.bin") for p in paths),
      "file path keeps the file name but masks the home-directory user")
addresses = [e.get("address") for e in io["socket_io"]["top_endpoints_by_time"] if e.get("address")]
check(bool(addresses) and all(a.endswith(".xxx") for a in addresses if re.fullmatch(r"[\d.]+", a.replace("xxx", "0"))),
      "socket address has its last IPv4 octet masked")

summary = json.loads(texts["jfr_summary"])
check(summary["path"] == rec, "the path a tool echoes back is exactly the path passed in")
check("-D<omitted>" in summary["jvm"]["jvmFlags"] and "-XX:HeapDumpPath=<omitted>" in summary["jvm"]["jvmFlags"],
      "unrecognized -D property and path-valued -XX flag are omitted")
check(summary["jvm"].get("programArgumentCount") == 2 and summary["jvm"].get("mainClassOrJar") == "RedactionWorkload",
      "program arguments are counted, not shown")

for i, t in enumerate(tools):
    r = out[100 + i]
    text = r["content"][0]["text"]
    check(r["isError"] and re.fullmatch(r"Error: [^\n]*\([A-Za-z0-9_$]+\)", text) is not None and corrupt not in text.split("(")[-1],
          f"{t} on a corrupted recording returns only an exception class name ({text[:70]})")

if failures:
    sys.exit(f"\n{len(failures)} check(s) failed")
print(f"\nAll {checks} redaction checks passed.")
EOF
