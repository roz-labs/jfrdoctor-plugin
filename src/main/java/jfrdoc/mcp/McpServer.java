package jfrdoc.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import jfrdoc.json.JsonObject;
import jfrdoc.tools.FrameworkCategorizer;
import jfrdoc.tools.JfrAllocationTool;
import jfrdoc.tools.JfrBaselineDiffTool;
import jfrdoc.tools.JfrExceptionsTool;
import jfrdoc.tools.JfrGcStatsTool;
import jfrdoc.tools.JfrIoTool;
import jfrdoc.tools.JfrLockContentionTool;
import jfrdoc.tools.JfrMemoryTool;
import jfrdoc.tools.JfrNativeMethodsTool;
import jfrdoc.tools.JfrSummaryTool;
import jfrdoc.tools.JfrTopMethodsTool;
import jfrdoc.tools.Tool;

/**
 * MCP server over stdio, built on the official MCP Java SDK
 * (io.modelcontextprotocol.sdk:mcp). The SDK owns JSON-RPC framing,
 * lifecycle (initialize/ping/notifications), error codes, and tool-input
 * schema validation — the hand-rolled version of all that was the source of
 * this plugin's crash-on-malformed-input findings.
 *
 * Every jfrdoc Tool implementation is unchanged: each still takes a
 * jfrdoc.json.JsonObject and returns a JSON string. This class only adapts
 * between the SDK's Map&lt;String,Object&gt; arguments and that JsonObject.
 */
public final class McpServer {

    static final String SERVER_NAME = "jfrdoc";
    // Kept in lockstep with pom.xml and .claude-plugin/plugin.json; the
    // three-way check in ci/check-version-sync.sh parses this exact line.
    static final String SERVER_VERSION = "0.4.0";

    /** Kept comfortably under Claude Code's 60s default MCP tool-call timeout. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(55);

    public static void main(String[] args) {
        // Headless mode for CI: `java -jar jfrdoc-mcp.jar diff --baseline a.jfr
        // --current b.jfr` runs jfr_baseline_diff directly and exits non-zero on
        // a regression, with no MCP handshake and no client involved. Everything
        // below this is the normal stdio-MCP-server path Claude Code uses.
        if (args.length > 0 && "diff".equals(args[0])) {
            System.exit(runDiffCli(Arrays.copyOfRange(args, 1, args.length)));
            return;
        }

        var originalOut = System.out;
        // Tool code (or a transitive library) writing to System.out must never
        // corrupt the protocol stream; stdout is reserved for JSON-RPC frames.
        System.setOut(System.err);

        List<Tool> tools = List.of(
                new JfrSummaryTool(),
                new JfrTopMethodsTool(),
                new JfrGcStatsTool(),
                new JfrAllocationTool(),
                new JfrMemoryTool(),
                new JfrLockContentionTool(),
                new JfrExceptionsTool(),
                new JfrIoTool(),
                new JfrNativeMethodsTool(),
                new JfrBaselineDiffTool());

        McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
        var transportProvider = new StdioServerTransportProvider(jsonMapper, System.in, originalOut);

        var toolSpecs = tools.stream().map(tool -> toolSpecification(tool, jsonMapper)).toList();

        io.modelcontextprotocol.server.McpServer.sync(transportProvider)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .validateToolInputs(true)
                .requestTimeout(REQUEST_TIMEOUT)
                .immediateExecution(true)
                .tools(toolSpecs)
                .build();

        System.err.println(SERVER_NAME + "-mcp " + SERVER_VERSION + " ready (" + tools.size() + " tools)");
    }

    // Every jfrdoc tool only reads a local .jfr file the caller names and
    // returns an aggregate — no writes, no side effects, same input always
    // produces the same output, no interaction with unpredictable external
    // systems. Advertising this lets a well-behaved client skip a
    // confirmation prompt it would otherwise show for an unannotated tool.
    static final McpSchema.ToolAnnotations READ_ONLY_ANALYSIS_TOOL = McpSchema.ToolAnnotations.builder()
            .readOnlyHint(true)
            .destructiveHint(false)
            .idempotentHint(true)
            .openWorldHint(false)
            .build();

    static McpServerFeatures.SyncToolSpecification toolSpecification(Tool tool, McpJsonMapper jsonMapper) {
        var schema = McpSchema.Tool.builder(tool.toolName())
                .description(tool.description())
                .inputSchema(jsonMapper, tool.inputSchema())
                .annotations(READ_ONLY_ANALYSIS_TOOL)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(schema)
                .callHandler((exchange, request) -> callTool(tool, request))
                .build();
    }

    /**
     * Generous ceiling above the ~1-10 KB a legitimate tool call produces
     * (per README). A recording engineered for extreme cardinality (many
     * thousands of distinct classes/sites/endpoints) could otherwise let
     * attacker-chosen strings balloon the response arbitrarily; replacing
     * rather than truncating keeps the response valid JSON instead of a
     * cut-off fragment.
     */
    static final int MAX_OUTPUT_CHARS = 250_000;

    static McpSchema.CallToolResult callTool(Tool tool, McpSchema.CallToolRequest request) {
        String pathGuard = validateJfrPath(request.arguments());
        String output = pathGuard != null ? pathGuard : executeSafely(tool, request.arguments());
        if (output.length() > MAX_OUTPUT_CHARS) {
            output = "Error: " + tool.toolName() + " output exceeded " + MAX_OUTPUT_CHARS
                    + " characters (" + output.length() + " chars) — likely a recording with unusually "
                    + "high-cardinality data (many distinct classes/sites/endpoints). Try a smaller top_n. "
                    + "Full output withheld.";
        }
        return McpSchema.CallToolResult.builder()
                .addTextContent(output)
                .isError(output.startsWith("Error:"))
                .build();
    }

    // Belt-and-suspenders: every jfrdoc tool only ever reads a local file via
    // Path.of()/Files, so neither of these is exploitable today — but
    // rejecting them explicitly, before the path ever reaches the filesystem
    // or the JFR parser, beats relying on Path/IO calls to fail incidentally.
    static final java.util.regex.Pattern URL_SCHEME =
            java.util.regex.Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://");

    /**
     * Every jfrdoc tool takes one or more path-shaped arguments — `path` on
     * the original nine tools, `baseline_path`/`current_path` on
     * jfr_baseline_diff — and only .jfr files are legitimate input for any
     * of them.
     */
    static String validateJfrPath(Map<String, Object> arguments) {
        for (var entry : arguments.entrySet()) {
            String key = entry.getKey();
            if (!key.equals("path") && !key.endsWith("_path")) continue;
            if (!(entry.getValue() instanceof String path)) continue;
            String error = validateSingleJfrPath(key, path);
            if (error != null) return error;
        }
        return null;
    }

    static String validateSingleJfrPath(String key, String path) {
        if (URL_SCHEME.matcher(path).find()) {
            return "Error: " + key + " must be a local filesystem path, not a URL (got: " + path + ")";
        }
        if (path.contains("..")) {
            return "Error: " + key + " must not contain '..' path-traversal segments";
        }
        if (!path.toLowerCase(Locale.ROOT).endsWith(".jfr")) {
            return "Error: only .jfr files are accepted for " + key + " (got: " + path + ")";
        }
        return null;
    }

    static String executeSafely(Tool tool, Map<String, Object> arguments) {
        long start = System.nanoTime();
        String outcome = "failed";
        try {
            String result = tool.execute(JsonObject.fromMap(arguments));
            outcome = result.startsWith("Error:") ? "returned an error" : "succeeded";
            return result;
        } catch (Throwable t) {
            // Catches Error (e.g. OutOfMemoryError on a huge/high-cardinality
            // recording) as well as RuntimeException: verified experimentally
            // that an escaped Error hangs this SDK version's callHandler
            // dispatch indefinitely instead of failing the request, so it must
            // never leave this method uncaught.
            //
            // Only the exception's class name is returned, never t.getMessage()
            // or t.toString() — a malformed/adversarial recording could cause a
            // parser exception whose message embeds fragments of file content,
            // and that must never reach the calling model's context.
            return "Error: " + tool.toolName() + " failed (" + t.getClass().getSimpleName() + ")";
        } finally {
            long millis = (System.nanoTime() - start) / 1_000_000;
            System.err.println(tool.toolName() + " " + outcome + " in " + millis + " ms");
        }
    }

    // --- `diff` CLI subcommand: a CI regression gate needs a plain exit code
    // and JSON on stdout, not an MCP session — so this bypasses the SDK
    // entirely and calls jfr_baseline_diff's logic directly.

    static final String DIFF_USAGE =
            "Usage: java -jar jfrdoc-mcp.jar diff --baseline <baseline.jfr> --current <current.jfr> "
                    + "[--container-memory-mb N] [--framework spring|quarkus|other] "
                    + "[--allocation-threshold-pct N] [--gc-pause-overhead-threshold-pp N] "
                    + "[--gc-p99-threshold-pct N] [--memory-threshold-pct N]\n"
                    + "Exits 0 on PASS, 1 on FAIL, 2 on a usage or input error. Always prints the "
                    + "comparison JSON to stdout first (except on a usage error).";

    static int runDiffCli(String[] args) {
        Map<String, String> flags = parseFlags(args);
        String baseline = flags.get("baseline");
        String current = flags.get("current");
        if (baseline == null || current == null) {
            System.err.println(DIFF_USAGE);
            return 2;
        }

        var baselinePath = Path.of(baseline);
        var currentPath = Path.of(current);
        for (var p : List.of(baselinePath, currentPath)) {
            if (!Files.exists(p) || !Files.isRegularFile(p)) {
                System.err.println("Error: JFR file not found: " + p);
                return 2;
            }
        }

        Integer containerMb = parsePositiveIntFlag(flags.get("container-memory-mb"));
        String framework = flags.getOrDefault("framework", "other");
        double allocThresholdPct = parseDoubleFlag(flags.get("allocation-threshold-pct"),
                JfrBaselineDiffTool.DEFAULT_ALLOCATION_RATE_THRESHOLD_PCT);
        double gcOverheadThresholdPp = parseDoubleFlag(flags.get("gc-pause-overhead-threshold-pp"),
                JfrBaselineDiffTool.DEFAULT_GC_PAUSE_OVERHEAD_THRESHOLD_PP);
        double gcP99ThresholdPct = parseDoubleFlag(flags.get("gc-p99-threshold-pct"),
                JfrBaselineDiffTool.DEFAULT_GC_P99_PAUSE_THRESHOLD_PCT);
        double memoryThresholdPct = parseDoubleFlag(flags.get("memory-threshold-pct"),
                JfrBaselineDiffTool.DEFAULT_MEMORY_THRESHOLD_PCT);

        try {
            var categorizer = FrameworkCategorizer.forFramework(framework);
            var result = JfrBaselineDiffTool.compare(baselinePath, currentPath, containerMb, framework, categorizer,
                    allocThresholdPct, gcOverheadThresholdPp, gcP99ThresholdPct, memoryThresholdPct);
            System.out.println(result.toString(2));
            return "FAIL".equals(result.get("verdict")) ? 1 : 0;
        } catch (IOException e) {
            System.err.println("Error: Could not read JFR file (" + e.getClass().getSimpleName() + ")");
            return 2;
        }
    }

    static Map<String, String> parseFlags(String[] args) {
        var flags = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) continue;
            String key = a.substring(2);
            String value = (i + 1 < args.length) ? args[++i] : null;
            if (value != null) flags.put(key, value);
        }
        return flags;
    }

    static Integer parsePositiveIntFlag(String v) {
        if (v == null) return null;
        try {
            int i = Integer.parseInt(v);
            return i > 0 ? i : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static double parseDoubleFlag(String v, double fallback) {
        if (v == null) return fallback;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
