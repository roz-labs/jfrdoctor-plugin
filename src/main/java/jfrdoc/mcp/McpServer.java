package jfrdoc.mcp;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import jfrdoc.json.JsonArray;
import jfrdoc.json.JsonException;
import jfrdoc.json.JsonObject;
import jfrdoc.json.JsonParser;
import jfrdoc.tools.JfrAllocationTool;
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
 * MCP server over stdio: newline-delimited JSON-RPC 2.0, JDK only.
 *
 * <p>jfrdoc used to run on the official MCP Java SDK, shipped as a 6 MB shaded
 * jar. It now ships as source that the JDK compiles at startup (see
 * {@code launcher/Launch.java}), so the protocol layer is this class. The SDK
 * had been adopted because an even earlier hand-rolled loop crashed on
 * malformed input; the rules that keep this one from repeating that:
 *
 * <ul>
 *   <li>Every line — reading, decoding and handling it — runs inside a
 *       catch-all, including {@link Error}s such as OutOfMemoryError. A line
 *       that fails gets a JSON-RPC error and the session carries on; only EOF
 *       or a closed stdin ends it.</li>
 *   <li>Lines are capped at {@link #MAX_LINE_BYTES} while being read, so an
 *       endless line can't exhaust memory; {@link JsonParser} caps nesting.
 *       Invalid UTF-8 is a parse error, never silently rewritten.</li>
 *   <li>Every tools/call gets exactly one response, unless it was cancelled.</li>
 *   <li>Error messages never echo request content back.</li>
 * </ul>
 *
 * <p>Tool calls run one at a time on a worker thread, so ping and
 * {@code notifications/cancelled} are still answered while a large recording
 * is being parsed, and memory stays bounded to one analysis at a time.
 */
public final class McpServer {

    static final String SERVER_NAME = "jfrdoc";
    // Kept in lockstep with .claude-plugin/plugin.json; the check in
    // ci/check-version-sync.sh parses this exact line.
    static final String SERVER_VERSION = "0.4.0";

    /** Newest first; an unknown client version gets the newest. */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    /** A legitimate request is well under 1 KB; anything past 1 MiB is refused unread. */
    static final int MAX_LINE_BYTES = 1 << 20;

    /**
     * Generous ceiling above the ~1-10 KB a legitimate tool call produces
     * (per README). A recording engineered for extreme cardinality (many
     * thousands of distinct classes/sites/endpoints) could otherwise let
     * attacker-chosen strings balloon the response arbitrarily; replacing
     * rather than truncating keeps the response valid JSON instead of a
     * cut-off fragment.
     */
    static final int MAX_OUTPUT_CHARS = 250_000;

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;

    // Every jfrdoc tool only reads a local .jfr file the caller names and
    // returns an aggregate — no writes, no side effects, same input always
    // produces the same output, no interaction with unpredictable external
    // systems. Advertising this lets a well-behaved client skip a
    // confirmation prompt it would otherwise show for an unannotated tool.
    static final JsonObject READ_ONLY_ANALYSIS_TOOL = new JsonObject()
            .put("readOnlyHint", true)
            .put("destructiveHint", false)
            .put("idempotentHint", true)
            .put("openWorldHint", false);

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Map<String, JsonObject> schemas = new LinkedHashMap<>();
    private final JsonArray toolList = new JsonArray();
    private final Writer out;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "jfrdoc-tool");
        t.setDaemon(true);
        return t;
    });
    private final Map<Object, Call> inFlight = new ConcurrentHashMap<>();

    /** A tools/call in the worker queue or running; cancellation suppresses its response. */
    private static final class Call {
        volatile boolean cancelled;
        volatile Future<?> future;
    }

    McpServer(List<Tool> toolset, OutputStream stdout) {
        this.out = new BufferedWriter(new OutputStreamWriter(stdout, StandardCharsets.UTF_8));
        for (Tool tool : toolset) {
            var schema = (JsonObject) JsonParser.parse(tool.inputSchema());
            tools.put(tool.toolName(), tool);
            schemas.put(tool.toolName(), schema);
            toolList.put(new JsonObject()
                    .put("name", tool.toolName())
                    .put("description", tool.description())
                    .put("inputSchema", schema)
                    .put("annotations", READ_ONLY_ANALYSIS_TOOL));
        }
    }

    public static void main(String[] args) throws InterruptedException {
        var originalOut = System.out;
        // Tool code (or the JDK) writing to System.out must never corrupt the
        // protocol stream; stdout is reserved for JSON-RPC frames.
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
                new JfrNativeMethodsTool());

        var server = new McpServer(tools, originalOut);
        System.err.println(SERVER_NAME + "-mcp " + SERVER_VERSION + " ready (" + tools.size() + " tools)");
        server.serve(System.in);
    }

    /** Reads until EOF, then lets queued tool calls finish so their responses go out. */
    void serve(InputStream stdin) throws InterruptedException {
        var in = new BufferedInputStream(stdin);
        var line = new ByteArrayOutputStream();
        CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        while (true) {
            try {
                line.reset();
                int status = readLine(in, line);
                if (status == EOF) break;
                if (status == TOO_LONG) {
                    sendError(JsonObject.NULL, INVALID_REQUEST, "Request exceeds " + MAX_LINE_BYTES + " bytes");
                    continue;
                }
                String text;
                try {
                    text = utf8.decode(ByteBuffer.wrap(line.toByteArray())).toString();
                } catch (CharacterCodingException e) {
                    sendError(JsonObject.NULL, PARSE_ERROR, "Parse error: invalid UTF-8");
                    continue;
                }
                handleLine(text);
            } catch (IOException e) {
                System.err.println("stdin closed (" + e.getClass().getSimpleName() + ")");
                break;
            } catch (Throwable t) {
                // e.g. OutOfMemoryError while a large analysis holds the heap:
                // drop this line, keep serving.
                reportInternalError(JsonObject.NULL, t);
            }
        }
        worker.shutdown();
        worker.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    static final int OK = 0;
    static final int EOF = 1;
    static final int TOO_LONG = 2;

    /**
     * Reads one line of raw bytes into {@code buf}, stopping at
     * {@link #MAX_LINE_BYTES}: the rest of an over-long line is consumed and
     * dropped, never buffered. A final line without a trailing newline still
     * counts. A trailing CR is stripped.
     */
    static int readLine(InputStream in, ByteArrayOutputStream buf) throws IOException {
        boolean tooLong = false;
        boolean any = false;
        int b;
        while ((b = in.read()) != -1) {
            any = true;
            if (b == '\n') break;
            if (buf.size() < MAX_LINE_BYTES) {
                buf.write(b);
            } else {
                tooLong = true;
            }
        }
        if (!any) return EOF;
        if (tooLong) return TOO_LONG;
        byte[] bytes = buf.toByteArray();
        if (bytes.length > 0 && bytes[bytes.length - 1] == '\r') {
            buf.reset();
            buf.write(bytes, 0, bytes.length - 1);
        }
        return OK;
    }

    void handleLine(String line) {
        if (line.isBlank()) return;
        Object id = JsonObject.NULL;
        try {
            Object parsed;
            try {
                parsed = JsonParser.parse(line);
            } catch (JsonException e) {
                sendError(JsonObject.NULL, PARSE_ERROR, "Parse error");
                return;
            }
            if (!(parsed instanceof JsonObject msg)) {
                sendError(JsonObject.NULL, INVALID_REQUEST, parsed instanceof JsonArray
                        ? "Batch requests are not supported" : "Invalid Request");
                return;
            }

            Object method = msg.opt("method");
            if (method == null && (msg.has("result") || msg.has("error"))) {
                // A response (or a peer's error reply, possibly with id null).
                // This server sends no requests, and responses are never answered.
                return;
            }

            Object rawId = msg.opt("id");
            // MCP: request ids are strings or integers, never null.
            if (rawId != null && !(rawId instanceof String) && !(rawId instanceof Long)) {
                sendError(JsonObject.NULL, INVALID_REQUEST, "Invalid Request: id must be a string or an integer");
                return;
            }
            if (rawId != null) id = rawId;
            if (!"2.0".equals(msg.opt("jsonrpc")) || !(method instanceof String m)) {
                sendError(id, INVALID_REQUEST, "Invalid Request");
                return;
            }
            Object rawParams = msg.opt("params");
            if (rawParams == JsonObject.NULL) rawParams = null;
            if (rawParams != null && !(rawParams instanceof JsonObject)) {
                if (rawId != null) sendError(id, INVALID_PARAMS, "params must be an object");
                return;
            }
            var params = rawParams == null ? new JsonObject() : (JsonObject) rawParams;

            if (rawId == null) {
                handleNotification(m, params);
            } else {
                handleRequest(rawId, m, params);
            }
        } catch (Throwable t) {
            // Last line of defence: no input may take the server down.
            reportInternalError(id, t);
        }
    }

    /** Best effort, and never throws: the heap may be exhausted when this runs. */
    void reportInternalError(Object id, Throwable t) {
        try {
            System.err.println("internal error handling a message (" + t.getClass().getSimpleName() + ")");
            sendError(id, INTERNAL_ERROR, "Internal error");
        } catch (Throwable ignored) {
            // Nothing more can be done for this message; the loop goes on.
        }
    }

    void handleNotification(String method, JsonObject params) {
        if (method.equals("notifications/cancelled")) {
            Object requestId = params.opt("requestId");
            Call call = requestId == null ? null : inFlight.get(requestId);
            if (call != null) {
                call.cancelled = true;
                var f = call.future;
                // A task cancelled before it starts never runs its finally
                // block, so its id must be released here or it stays "in
                // progress" forever. (Removing a running one is harmless too:
                // its own finally only removes its own Call.)
                if (f != null && f.cancel(true)) inFlight.remove(requestId, call);
            }
        }
        // notifications/initialized and anything else: nothing to do.
    }

    void handleRequest(Object id, String method, JsonObject params) {
        switch (method) {
            case "initialize" -> {
                if (!(params.opt("protocolVersion") instanceof String requested)) {
                    sendError(id, INVALID_PARAMS, "initialize requires a protocolVersion string");
                    return;
                }
                String version = PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(0);
                sendResult(id, new JsonObject()
                        .put("protocolVersion", version)
                        .put("capabilities", new JsonObject()
                                .put("tools", new JsonObject().put("listChanged", false)))
                        .put("serverInfo", new JsonObject()
                                .put("name", SERVER_NAME)
                                .put("version", SERVER_VERSION)));
            }
            case "ping" -> sendResult(id, new JsonObject());
            case "tools/list" -> sendResult(id, new JsonObject().put("tools", toolList));
            case "tools/call" -> startToolCall(id, params);
            default -> sendError(id, METHOD_NOT_FOUND, "Method not found");
        }
    }

    void startToolCall(Object id, JsonObject params) {
        if (!(params.opt("name") instanceof String name) || !tools.containsKey(name)) {
            sendError(id, INVALID_PARAMS, "Unknown tool");
            return;
        }
        Object rawArgs = params.opt("arguments");
        if (rawArgs != null && rawArgs != JsonObject.NULL && !(rawArgs instanceof JsonObject)) {
            sendError(id, INVALID_PARAMS, "arguments must be an object");
            return;
        }
        var args = rawArgs instanceof JsonObject o ? o.withoutNulls() : new JsonObject();

        var call = new Call();
        if (inFlight.putIfAbsent(id, call) != null) {
            sendError(id, INVALID_REQUEST, "A request with this id is already in progress");
            return;
        }
        call.future = worker.submit(() -> {
            try {
                if (call.cancelled) return;
                var result = callTool(tools.get(name), schemas.get(name), args);
                if (!call.cancelled) sendResult(id, result);
            } catch (Throwable t) {
                // callTool already catches tool failures; this covers building
                // or sending the response (e.g. OutOfMemoryError), so the
                // request still gets exactly one answer.
                if (!call.cancelled) reportInternalError(id, t);
            } finally {
                inFlight.remove(id, call);
            }
        });
    }

    static JsonObject callTool(Tool tool, JsonObject schema, JsonObject args) {
        String output = validateArguments(tool.toolName(), schema, args);
        if (output == null) output = validateJfrPath(args);
        if (output == null) output = executeSafely(tool, args);
        if (output.length() > MAX_OUTPUT_CHARS) {
            output = "Error: " + tool.toolName() + " output exceeded " + MAX_OUTPUT_CHARS
                    + " characters (" + output.length() + " chars) — likely a recording with unusually "
                    + "high-cardinality data (many distinct classes/sites/endpoints). Try a smaller top_n. "
                    + "Full output withheld.";
        }
        return new JsonObject()
                .put("content", new JsonArray().put(new JsonObject()
                        .put("type", "text")
                        .put("text", output)))
                .put("isError", output.startsWith("Error:"));
    }

    /**
     * Checks arguments against the tool's own input schema — the subset
     * {@link Tool#schema} emits: required members, string/integer/number
     * types, and string enums. Returned as a tool error (not a protocol
     * error) so the model sees what to fix. Unknown members are ignored, as
     * JSON Schema allows by default.
     */
    static String validateArguments(String toolName, JsonObject schema, JsonObject args) {
        var props = schema.opt("properties") instanceof JsonObject p ? p : new JsonObject();
        if (schema.opt("required") instanceof JsonArray required) {
            for (int i = 0; i < required.size(); i++) {
                if (required.get(i) instanceof String key && args.opt(key) == null) {
                    return "Error: " + toolName + " requires argument '" + key + "'";
                }
            }
        }
        for (String key : args.keys()) {
            if (!(props.opt(key) instanceof JsonObject spec)) continue;
            Object value = args.opt(key);
            String type = spec.opt("type") instanceof String t ? t : "";
            boolean ok = switch (type) {
                case "string" -> value instanceof String s && inEnum(spec, s);
                case "integer" -> isInt(value);
                case "number" -> value instanceof Number;
                default -> true;
            };
            if (!ok) {
                return "Error: " + toolName + " argument '" + key + "' must be "
                        + (spec.has("enum") ? "one of " + spec.opt("enum") : "a " + type);
            }
        }
        return null;
    }

    static boolean inEnum(JsonObject spec, String value) {
        if (!(spec.opt("enum") instanceof JsonArray allowed)) return true;
        for (int i = 0; i < allowed.size(); i++) {
            if (value.equals(allowed.get(i))) return true;
        }
        return false;
    }

    /** JSON Schema "integer": any integral value, here also bounded to Java int. */
    static boolean isInt(Object value) {
        if (value instanceof Long l) return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE;
        if (value instanceof Double d) return d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE;
        return false;
    }

    // Belt-and-suspenders: every jfrdoc tool only ever reads a local file via
    // Path.of()/Files, so neither of these is exploitable today — but
    // rejecting them explicitly, before the path ever reaches the filesystem
    // or the JFR parser, beats relying on Path/IO calls to fail incidentally.
    static final Pattern URL_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://");

    /** Every jfrdoc tool takes a `path`; only .jfr files are legitimate input. */
    static String validateJfrPath(JsonObject arguments) {
        if (!(arguments.opt("path") instanceof String path)) return null;
        if (URL_SCHEME.matcher(path).find()) {
            return "Error: path must be a local filesystem path, not a URL (got: " + path + ")";
        }
        if (path.contains("..")) {
            return "Error: path must not contain '..' path-traversal segments";
        }
        if (!path.toLowerCase(Locale.ROOT).endsWith(".jfr")) {
            return "Error: only .jfr files are accepted (got: " + path + ")";
        }
        return null;
    }

    static String executeSafely(Tool tool, JsonObject arguments) {
        long start = System.nanoTime();
        String outcome = "failed";
        try {
            String result = tool.execute(arguments);
            outcome = result.startsWith("Error:") ? "returned an error" : "succeeded";
            return result;
        } catch (Throwable t) {
            // Catches Error (e.g. OutOfMemoryError on a huge/high-cardinality
            // recording) as well as RuntimeException, so the worker thread
            // always survives to run the next call.
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

    void sendResult(Object id, JsonObject result) {
        send(new JsonObject().put("jsonrpc", "2.0").put("id", id).put("result", result));
    }

    void sendError(Object id, int code, String message) {
        send(new JsonObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", new JsonObject().put("code", code).put("message", message)));
    }

    /** One compact JSON document per line; JsonWriter escapes every newline inside strings. */
    private void send(JsonObject message) {
        String frame = message.toString();
        synchronized (out) {
            try {
                out.write(frame);
                out.write('\n');
                out.flush();
            } catch (IOException e) {
                System.err.println("stdout closed (" + e.getClass().getSimpleName() + ")");
            }
        }
    }
}
