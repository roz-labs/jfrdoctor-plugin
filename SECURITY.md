# Security

## No network egress

jfrdoc is a local MCP tool: it reads a `.jfr` file you point it at and
returns aggregated JSON over MCP stdio to the calling Claude Code session.
It makes no outbound network connections and has no telemetry.

- **True by construction.** `src/main/java` has no imports of `java.net.*`,
  `javax.net.*`, or `java.rmi.*` — the only file I/O is reading the `.jfr`
  you name via `jdk.jfr.consumer.RecordingFile`. Verify yourself:
  `grep -rn "java\.net\." src/main/java`.
- **True of the shipped jar's active code path.** jfrdoc's build depends on
  one third-party library, the MCP Java SDK
  (`io.modelcontextprotocol.sdk:mcp`), which bundles HTTP/SSE client and
  server transports for callers who need them. jfrdoc only ever constructs
  `StdioServerTransportProvider` (see `src/main/java/jfrdoc/mcp/McpServer.java`)
  — those transports are never instantiated, and `pom.xml`'s shade-plugin
  filters exclude the confirmed-dead client package and HTTP/SSE
  server-transport classes from the shipped `lib/jfrdoc-mcp.jar` so they
  aren't even present to be misused. (Project Reactor is bundled and *is*
  used — it's a direct dependency of the stdio transport itself, not of any
  HTTP path.)

## What data flows to your model, and why

jfrdoc's tools parse the `.jfr` file you name and return an aggregate: class
and method names, aggregate counts and durations, file paths and socket
endpoints touched by the profiled process, JVM startup arguments, and sample
exception messages. This is inherent to what the tools do — class/method
names are the tools' actual purpose (identifying hotspots, allocation sites,
throwing sites) and are never redacted.

What's *not* inherent — data the profiled application's own runtime state
can incidentally carry, unrelated to code structure — is minimized:

- **Exception messages** (`jfr_exceptions`' `sample_message`): an
  application's own exception text is freeform and can embed emails,
  credentials, or connection strings. Redacted for email addresses,
  key=value secrets (password/secret/token/credential/api-key/auth-shaped
  keys), and URL userinfo credentials before being included, truncated to
  120 characters.
- **File paths** (`jfr_io`'s `top_files_by_time`, `repeated_file_path`,
  `slowest_operation_target`): OS home-directory username segments
  (`/home/<user>/…`, `/Users/<user>/…`, `C:\Users\<user>\…`) are masked. The
  rest of the path — including the filename, which is the tool's actual
  diagnostic payload (which file is slow) — is left intact.
- **Socket addresses** (`jfr_io`'s `address` field): the last octet of a raw
  IPv4 address is masked. Hostnames (`host`/`endpoint`) are deliberately
  *not* touched — they're the tool's core diagnostic signal (which service
  is slow, which database is chatty) and are organizational infrastructure
  information, not personal data; masking them would make the tool useless
  for its stated purpose.
- **JVM/program arguments** (`jfr_summary`'s `jvmArguments`/`javaArguments`):
  redacted with the same email/secret/URL-userinfo patterns as exception
  messages, on top of the original narrower `-D…password=…`-style check.
- **Error responses**: every tool-call error returns only the failing
  exception's class name, never its message text, so a malformed or
  adversarial recording can't smuggle file content into an error string.
  The `path` a tool echoes back matches what you passed in — jfrdoc never
  resolves it to an absolute filesystem path on your behalf.

**None of this is exhaustive.** These are pattern-based, best-effort
measures against well-defined shapes (an email address, a `key=value`
secret, a home-directory username, an IPv4 octet) — they will not catch
every way a person's name, a customer identifier, or a secret can appear in
freeform application text. Two things are true regardless of jfrdoc's own
processing:

1. Class names, method names, and thread/stack structure are shown in full
   — if your codebase's own naming carries information you don't want
   shared with your model provider, treat that as inherent to profiling.
2. Independently of jfrdoc, a `.jfr` file captured with JFR's `default` or
   `profile` settings stores every environment variable *with its value*
   and the full command line of every process on the host
   (`jdk.InitialEnvironmentVariable`, `jdk.SystemProcess`,
   `jdk.InitialSystemProperty`). jfrdoc's tools never read those event
   types, so they never reach the model — but they are in the file. Treat a
   recording as sensitive before sharing it, committing it, or attaching it
   to a bug report; see the README for how to suppress them at the source.

## Reporting a vulnerability

Please report security issues privately using GitHub's "Report a
vulnerability" feature under this repository's Security tab, rather than
opening a public issue. jfrdoc is a local-only tool with no hosted service,
so most legitimate reports will concern the shipped jar's bundled
dependencies, the MCP protocol boundary, or a gap in the redaction behavior
described above.
