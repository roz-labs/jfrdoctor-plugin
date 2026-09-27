# Security

## No network egress

jfrdoc is a local MCP tool: it reads a `.jfr` file you point it at and
returns aggregated JSON over MCP stdio to the calling Claude Code session.
It makes no outbound network connections and has no telemetry.

- **True by construction.** `src/main/java` has no imports of `java.net.*`,
  `javax.net.*`, or `java.rmi.*` — the only file I/O is reading the `.jfr`
  you name via `jdk.jfr.consumer.RecordingFile`. Verify yourself:
  `grep -rn "java\.net\." src/main/java`.
- **Nothing compiled, nothing downloaded.** The plugin ships only readable
  source and depends on nothing outside the JDK. `launcher/Launch.java`
  compiles `src/main/java` in memory with the JDK's own compiler each time
  the server starts, and resolves classes and resources from the plugin's
  own tree — never the classpath or your working directory. There is no
  package install, no download, and no file written to disk.

## What data flows to your model, and why

jfrdoc's tools parse the `.jfr` file you name and return an aggregate: class
and method names, aggregate counts and durations, file paths and socket
endpoints touched by the profiled process, a sanitized view of the JVM's
startup flags, and sample exception messages. This is inherent to what the
tools do — class/method names are the tools' actual purpose (identifying
hotspots, allocation sites, throwing sites) and are never redacted.

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
- **JVM/program arguments** (`jfr_summary`'s `jvm` block): never sent
  verbatim. A command line can carry a secret in any shape, so instead of
  guessing which values are secret, jfrdoc keeps only what it can prove is
  safe by shape: `-XX:+Flag` switches, numeric values (`-Xmx512m`,
  `MaxRAMPercentage=75.0`), `-D` property *names* without values, agent jar
  file names without options, and the names of standard launcher options.
  Every other value reads `<omitted>`; paths, classpath entries and
  unrecognized tokens are dropped. Of the program arguments, only the main
  class or jar file name and a count are reported. See
  `src/main/java/jfrdoc/tools/JvmArguments.java`.
- **Error responses**: every tool-call error returns only the failing
  exception's class name, never its message text, so a malformed or
  adversarial recording can't smuggle file content into an error string.
  The `path` a tool echoes back matches what you passed in — jfrdoc never
  resolves it to an absolute filesystem path on your behalf.

**None of the pattern-based measures is exhaustive.** Apart from the JVM
argument allowlist, these are best-effort measures against well-defined
shapes (an email address, a `key=value` secret, a home-directory username,
an IPv4 octet) — they will not catch
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
so most legitimate reports will concern the MCP protocol boundary
(`src/main/java/jfrdoc/mcp/McpServer.java`,
`src/main/java/jfrdoc/json/JsonParser.java`), the launcher, or a gap in the
redaction behavior described above.
