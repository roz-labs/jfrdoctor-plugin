<h1 align="center">jfrdoc</h1>

<p align="center"><strong>JVM Flight Recorder analysis as a Claude Code plugin.</strong></p>

<p align="center">
Hand Claude a <code>.jfr</code> recording and get a structured performance report —
CPU hotspots, GC behavior, allocation pressure, memory footprint, lock contention,
exceptions, I/O wait, and native execution — with framework-aware attribution
and concrete, evidence-backed recommendations.
</p>

<p align="center">
<a href="https://github.com/roz-labs/jfrdoctor-plugin/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/roz-labs/jfrdoctor-plugin?label=release"></a>
</p>

jfrdoc is a plugin: an MCP server exposing nine JFR analysis tools, plus a skill
that drives them into a fixed report shape. No API key, no credentials, no
dependencies: the plugin is plain Java source that the JDK compiles when the
server starts, so it needs nothing beyond a JDK — no build step, no download.

---

## Where this runs

The tools are a local process (`java`), so they need a machine to run on:

| Surface | Skill | The nine tools |
|-|-|-|
| Claude Code (CLI) | ✅ | ✅ tested |
| Claude Desktop — **Code** tab | ✅ | ✅ same runtime as the CLI |
| Cowork — local session | ✅ | ✅ tested |
| Cowork — remote session | ✅ loads | ❌ cannot run |
| claude.ai in a browser | ✅ loads | ❌ cannot run |

Wherever Claude runs in the cloud rather than on your machine — a browser session,
or a remote Cowork session — there is no local JVM and no local `.jfr` file, so the
skill loads but every tool it calls is missing and no report can be produced. Use
jfrdoc from Claude Code or the Claude Desktop app.

## How it's put together

| Piece | What it does |
|-|-|
| **MCP server** (`src/main/java`, started by `launcher/Launch.java`) | JDK-only stdio JSON-RPC server exposing 9 tools that parse `.jfr` files via the JDK's `jdk.jfr.consumer` — deterministic JSON out, no model involved |
| **Skill** (`skills/analyze-jfr/`) | Teaches Claude the analysis workflow (which tool to call when) and the exact report structure + interpretation rules |
| **Plugin manifest** (`.claude-plugin/`) | Bundles both so one install wires everything up |

The tools do the parsing (fast, deterministic, local); the model does the
synthesis (reading the numbers, writing the report). Your recording never
leaves the machine except as the aggregated JSON the model reads.

**Everything the plugin runs:** one process, `java launcher/Launch.java`,
started by Claude Code. The launcher compiles the plugin's own
`src/main/java` in memory with the JDK's compiler (about 1–3 seconds at each
server start), then serves the nine tools over stdin/stdout. It reads only
the `.jfr` files you name, opens no network connections, reads no
environment variables or credentials, downloads nothing, and writes no files.
See [SECURITY.md](SECURITY.md) for exactly what reaches the model.

## Install

Prerequisite: **JDK 21+** on `PATH` — a full JDK, not just a JRE, because the
server is compiled from source at startup. Nothing else to install.

```
/plugin marketplace add roz-labs/jfrdoctor-plugin
/plugin install jfrdoc@roz-labs
```

Adding this marketplace by raw URL is not supported — the entry uses a
repo-relative source, so the whole repository has to be cloned.

Then point it at any `.jfr` file you have:

> analyze recording.jfr — it's a plain Java batch app, container memory limit is 1000Mi, no CPU limit

No recording ships with this repo (see [Demo recording](#demo-recording) for why),
but you can generate one in 30 seconds:

```bash
./samples/gen-sample.sh   # writes samples/sample.jfr
```

[`samples/example-report.md`](samples/example-report.md) is a real end-to-end run
of exactly that flow against that generated recording — committed verbatim.

## Tools

| Tool | Purpose |
|-|-|
| `jfr_summary` | Recording metadata, JVM info, which event families are present |
| `jfr_top_methods` | On-CPU Java hotspots with user/framework/JDK attribution |
| `jfr_gc_stats` | Collector config, pause distribution, anomalies |
| `jfr_allocation` | Allocation rate, top classes, top sites |
| `jfr_memory` | Heap/metaspace/code-cache/threads/NMT, container-fit verdict |
| `jfr_lock_contention` | Monitor contention + thread parking, benign-park filtering |
| `jfr_exceptions` | Per-class throw rates, control-flow-smell detection |
| `jfr_io` | File/socket blocking time above the JFR ~10ms threshold |
| `jfr_native_methods` | Native execution with wait-vs-CPU disambiguation |

Every tool takes a `path` (must end in `.jfr`) and returns aggregated JSON,
typically 1–10 KB.

## Development

Prerequisite: **JDK 21+** on `PATH`. There is no build step and no build tool.

```bash
./test/unit.sh                 # JSON parser + JVM-argument sanitizer unit tests
./samples/gen-sample.sh        # generate the 30-second demo recording
./ci/check-sample-privacy.sh   # assert no recording leaks host data
./test/smoke.sh                # protocol, malformed input, all 9 tools over stdio
./ci/check-plugin-files.sh     # plugin ships only readable source (no binaries)
./ci/check-version-sync.sh     # plugin.json / McpServer.java agree
```

To try the server against a project without installing the plugin, point
Claude Code at your clone (replace the path):

```bash
claude mcp add jfrdoc -- java /path/to/jfrdoctor-plugin/launcher/Launch.java /path/to/jfrdoctor-plugin
```

Source layout: `launcher/Launch.java` (compiles `src/main/java` in memory and
starts the server), `src/main/java/jfrdoc/mcp` (the stdio JSON-RPC server —
lifecycle, tool registration, argument validation), `src/main/java/jfrdoc/json`
(a strict JSON parser for the wire protocol and a writer for tool output),
`src/main/java/jfrdoc/tools` (the nine analyzers, framework categorizer and
redaction), `src/main/resources/frameworks` (package-prefix lists for
CPU/allocation attribution), `test/` (unit and smoke tests).

**Why no MCP SDK:** jfrdoc 0.3 ran on the official
[MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk), shipped as a
~6 MB shaded jar. The Claude plugin directory's security scan can't read
compiled code, so every version was held for manual review; 0.4 went back to
a JDK-only server so the plugin can ship as readable source. The SDK had
originally replaced an even earlier hand-rolled loop whose parser crashed on
malformed input (a bad `\uXXXX` escape, deeply nested JSON, a non-string
field where a string was expected). The current server is written against
exactly those failures: nesting is capped, escapes and numbers are validated
against the JSON grammar, lines are length-capped while being read, and every
message is handled inside a catch-all that answers with a JSON-RPC error.
`test/unit.sh` and `test/smoke.sh` replay each of those inputs, and more,
against it.

## Demo recording

`samples/sample.jfr` is generated, never committed. JFR's `profile` settings
record three event types that describe the **host**, not the profiled workload:
`jdk.InitialEnvironmentVariable` (every environment variable, **with its value**),
`jdk.SystemProcess` (every process on the machine, full command line), and
`jdk.InitialSystemProperty`. A recording made on a developer box or in CI
therefore carries credentials, tokens and private paths that have nothing to do
with the demo.

`samples/gen-sample.sh` disables all three, none of which any jfrdoc tool reads.
`ci/check-sample-privacy.sh` enforces both halves in CI: no `.jfr` may be tracked
by git, and any recording on disk must contain zero events of those types.

## Honest limitations

- **Large recordings** — a multi-GB recording keeps the JFR-parsing thread
  busy for the duration of the parse, and each tool re-reads the file (up to
  9 full passes for a complete analysis). Fine for typical minutes-long
  recordings, not yet for huge ones; there's no size preflight or warning.
- **Startup compiles the server** — each time Claude Code starts the MCP
  server, the launcher compiles ~4,000 lines of Java first, which adds about
  1–3 seconds before the tools are listed. Nothing is cached between starts.
- **One tool call at a time** — calls run one after another on a single
  worker thread, which keeps memory to one analysis at a time. Ping and
  cancellation are still answered while a call runs. Cancelling a queued call
  drops it; cancelling a running one interrupts it, but a parse that doesn't
  check for interruption runs to completion (its result is discarded).
- **A tool call throwing `Error` (not `Exception`)** — e.g. `OutOfMemoryError`
  on a huge or high-cardinality recording — is caught defensively in
  `McpServer.executeSafely` and returned as a normal tool error rather than
  left to propagate. This is untested against a real OOM (no fixture large
  enough to trigger one was available while writing this); treat it as a
  defensive measure, not a verified guarantee.
- **Report quality tracks the client's model** — the skill pins the structure
  and the interpretation rules, but a weaker model writes a weaker narrative.
- **Data egress** — tool output includes class names, file paths, socket
  endpoints and sample exception messages from the profiled app; it flows into
  the model context like any other tool result. JVM startup arguments are
  never passed on verbatim: `jfr_summary` reports switches, numeric sizes and
  `-D` property *names* only, never their values (details in
  [SECURITY.md](SECURITY.md)). Don't analyze recordings whose metadata you
  can't share with your model provider.
- **Recordings contain more than your application** — independently of jfrdoc,
  a `.jfr` captured with JFR's `default` or `profile` settings stores every
  environment variable *with its value* and the full command line of every
  process on the host. jfrdoc's tools never read those events, so they don't
  reach the model, but they are in the file. Treat a recording as sensitive
  before sharing it, committing it, or attaching it to a bug report — run
  `jfr summary` and `jfr print --events jdk.InitialEnvironmentVariable` on it
  first. Recording with
  `jdk.InitialEnvironmentVariable#enabled=false,jdk.SystemProcess#enabled=false,jdk.InitialSystemProperty#enabled=false`
  suppresses them at the source, and costs jfrdoc nothing.
- JFR I/O events are threshold-gated (~10ms): absence of I/O events means no
  *slow* I/O, not no I/O.

## License

MIT — see [LICENSE](LICENSE).
