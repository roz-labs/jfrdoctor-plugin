package jfrdoc.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import jfrdoc.json.JsonArray;
import jfrdoc.json.JsonObject;

public class JfrIoTool implements Tool {

    static final int DEFAULT_TOP_N = 10;
    static final int MIN_TOP_N = 1;
    static final int MAX_TOP_N = 50;

    static final String THRESHOLD_NOTE =
            "Only I/O operations exceeding the JFR threshold (~10ms default in profile settings) are captured. "
                    + "Fast I/O operations are not represented in this analysis. "
                    + "Absence of events means no SLOW I/O, not no I/O.";

    @Override
    public String toolName() {
        return "jfr_io";
    }

    @Override
    public String description() {
        return "Analyzes file and socket I/O wait from jdk.FileRead/FileWrite/SocketRead/SocketWrite events. "
                + "IMPORTANT: these events only fire for I/O operations exceeding the JFR threshold "
                + "(~10ms in profile settings) — fast I/O is invisible. "
                + "Returns top files and socket endpoints by blocking time, total I/O blocking time, "
                + "and the dominant I/O type. Use this to find slow disk access, slow downstream services, "
                + "or chatty database connections. Call this when summary.notable_events_present.io is true.";
    }

    enum Field { path, top_n }

    @Override
    public String inputSchema() {
        return Tool.schema(
                Prop.string(Field.path,
                        "Absolute or relative filesystem path to the .jfr file"),
                Prop.integer(Field.top_n,
                        "Number of top files / socket endpoints to return (clamped to [1,50]; default 10)").optional()
        );
    }

    @Override
    public String execute(JsonObject input) {
        if (!input.has(Field.path.name())) {
            return "Error: Missing required parameter: path";
        }
        var path = Path.of(input.getString(Field.path.name()));
        if (!Files.exists(path)) {
            return "Error: JFR file not found: " + path;
        }
        if (!Files.isRegularFile(path)) {
            return "Error: Not a regular file: " + path;
        }

        int topN = DEFAULT_TOP_N;
        if (input.has(Field.top_n.name())) {
            topN = clamp(input.getInt(Field.top_n.name()), MIN_TOP_N, MAX_TOP_N);
        }

        try {
            return analyze(path, topN).toString(2);
        } catch (IOException e) {
            return "Error: Could not read JFR file: " + e.getMessage();
        }
    }

    static JsonObject analyze(Path path, int topN) throws IOException {
        var fileAgg = new LinkedHashMap<String, FileStats>();
        var socketAgg = new LinkedHashMap<String, EndpointStats>();

        long fileReadEvents = 0, fileWriteEvents = 0;
        long fileReadNanos = 0, fileWriteNanos = 0;
        long fileReadBytes = 0, fileWriteBytes = 0;

        long socketReadEvents = 0, socketWriteEvents = 0;
        long socketReadNanos = 0, socketWriteNanos = 0;
        long socketReadBytes = 0, socketWriteBytes = 0;

        long slowestOpNanos = 0;
        String slowestOpTarget = null;

        Instant earliest = null;
        Instant latest = null;

        try (var rf = new RecordingFile(path)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String type = e.getEventType().getName();

                // Recording span must come from every event, not just I/O
                // events — otherwise duration_seconds (and io_blocking_pct_of
                // _recording derived from it) reflects only the window I/O
                // happened to occur in, not the actual recording length.
                var ts = e.getStartTime();
                if (earliest == null || ts.isBefore(earliest)) earliest = ts;
                if (latest == null || ts.isAfter(latest)) latest = ts;

                boolean isFileRead = "jdk.FileRead".equals(type);
                boolean isFileWrite = "jdk.FileWrite".equals(type);
                boolean isSocketRead = "jdk.SocketRead".equals(type);
                boolean isSocketWrite = "jdk.SocketWrite".equals(type);
                if (!isFileRead && !isFileWrite && !isSocketRead && !isSocketWrite) continue;

                Duration d = tryGetDuration(e, "duration");
                long ns = d == null ? 0L : d.toNanos();

                if (isFileRead || isFileWrite) {
                    String filePath = tryGetString(e, "path");
                    if (filePath == null || filePath.isEmpty()) filePath = "<unknown>";

                    long bytes;
                    if (isFileRead) {
                        bytes = nonNegativeBytes(tryGetLong(e, "bytesRead"));
                        fileReadEvents++;
                        fileReadNanos += ns;
                        fileReadBytes += bytes;
                    } else {
                        bytes = nonNegativeBytes(tryGetLong(e, "bytesWritten"));
                        fileWriteEvents++;
                        fileWriteNanos += ns;
                        fileWriteBytes += bytes;
                    }

                    var stats = fileAgg.computeIfAbsent(filePath, k -> new FileStats());
                    if (isFileRead) stats.readEvents++; else stats.writeEvents++;
                    stats.totalNanos += ns;
                    stats.totalBytes += bytes;
                    if (ns > stats.maxNanos) stats.maxNanos = ns;
                    String site = topFrameSignature(e.getStackTrace());
                    if (site != null) stats.callSiteCounts.merge(site, 1L, Long::sum);

                    if (ns > slowestOpNanos) {
                        slowestOpNanos = ns;
                        slowestOpTarget = filePath;
                    }
                } else {
                    String endpoint = endpointKey(e);
                    String address = tryGetString(e, "address");
                    long bytes;
                    if (isSocketRead) {
                        bytes = nonNegativeBytes(tryGetLong(e, "bytesRead"));
                        socketReadEvents++;
                        socketReadNanos += ns;
                        socketReadBytes += bytes;
                    } else {
                        bytes = nonNegativeBytes(tryGetLong(e, "bytesWritten"));
                        socketWriteEvents++;
                        socketWriteNanos += ns;
                        socketWriteBytes += bytes;
                    }

                    var stats = socketAgg.computeIfAbsent(endpoint, k -> new EndpointStats());
                    if (isSocketRead) stats.readEvents++; else stats.writeEvents++;
                    stats.totalNanos += ns;
                    stats.totalBytes += bytes;
                    if (ns > stats.maxNanos) stats.maxNanos = ns;
                    if (stats.address == null && address != null && !address.isEmpty()) stats.address = address;
                    String site = topFrameSignature(e.getStackTrace());
                    if (site != null) stats.callSiteCounts.merge(site, 1L, Long::sum);

                    if (ns > slowestOpNanos) {
                        slowestOpNanos = ns;
                        slowestOpTarget = endpoint;
                    }
                }
            }
        }

        double durationSeconds = 0.0;
        if (earliest != null && latest != null) {
            durationSeconds = Duration.between(earliest, latest).toMillis() / 1000.0;
        }

        long fileTotalNanos = fileReadNanos + fileWriteNanos;
        long socketTotalNanos = socketReadNanos + socketWriteNanos;
        long totalIoNanos = fileTotalNanos + socketTotalNanos;
        long totalIoEvents = fileReadEvents + fileWriteEvents + socketReadEvents + socketWriteEvents;

        var result = new JsonObject();

        var recording = new JsonObject()
                .put("path", path.toAbsolutePath().toString())
                .put("duration_seconds", round1(durationSeconds));
        result.put("recording", recording);

        result.put("file_io", buildFileIo(fileAgg,
                fileReadEvents, fileWriteEvents,
                fileReadNanos, fileWriteNanos,
                fileReadBytes, fileWriteBytes,
                topN));

        result.put("socket_io", buildSocketIo(socketAgg,
                socketReadEvents, socketWriteEvents,
                socketReadNanos, socketWriteNanos,
                socketReadBytes, socketWriteBytes,
                topN));

        String dominant;
        if (totalIoNanos == 0) dominant = "none";
        else if (socketTotalNanos > fileTotalNanos) dominant = "socket";
        else dominant = "file";

        // io_blocking_pct_of_recording can exceed 100% when many threads block
        // concurrently — this is cumulative wall-time across threads, not a single timeline.
        double ioPct = durationSeconds <= 0 ? 0.0
                : 100.0 * toMs(totalIoNanos) / (durationSeconds * 1000.0);

        var summary = new JsonObject()
                .put("total_io_blocking_time_ms", round1(toMs(totalIoNanos)))
                .put("io_blocking_pct_of_recording", round1(ioPct))
                .put("dominant_io_type", dominant)
                .put("slowest_single_operation_ms", round1(toMs(slowestOpNanos)))
                .put("slowest_operation_target", slowestOpTarget == null ? JsonObject.NULL : slowestOpTarget);
        result.put("summary", summary);

        result.put("signals", buildSignals(fileAgg, socketAgg,
                fileTotalNanos, socketTotalNanos, totalIoEvents));

        result.put("threshold_note", THRESHOLD_NOTE);

        return result;
    }

    static JsonObject buildFileIo(Map<String, FileStats> agg,
                                  long readEvents, long writeEvents,
                                  long readNanos, long writeNanos,
                                  long readBytes, long writeBytes,
                                  int topN) {
        var fio = new JsonObject();
        fio.put("read_events", readEvents);
        fio.put("write_events", writeEvents);
        fio.put("total_read_time_ms", round1(toMs(readNanos)));
        fio.put("total_write_time_ms", round1(toMs(writeNanos)));
        fio.put("total_bytes_read_mb", round1(toMb(readBytes)));
        fio.put("total_bytes_written_mb", round1(toMb(writeBytes)));

        var list = new ArrayList<>(agg.entrySet());
        list.sort(Comparator.<Map.Entry<String, FileStats>>comparingLong(en -> en.getValue().totalNanos).reversed());

        var arr = new JsonArray();
        int rank = 1;
        for (var en : list) {
            if (rank > topN) break;
            var s = en.getValue();
            long total = s.readEvents + s.writeEvents;
            double avgMs = total == 0 ? 0.0 : toMs(s.totalNanos) / total;
            var entry = new JsonObject()
                    .put("rank", rank)
                    .put("path", en.getKey())
                    .put("read_events", s.readEvents)
                    .put("write_events", s.writeEvents)
                    .put("total_time_ms", round1(toMs(s.totalNanos)))
                    .put("total_bytes_mb", round1(toMb(s.totalBytes)))
                    .put("avg_time_ms", round1(avgMs))
                    .put("max_time_ms", round1(toMs(s.maxNanos)))
                    .put("top_call_site", dominantKey(s.callSiteCounts));
            arr.put(entry);
            rank++;
        }
        fio.put("top_files_by_time", arr);
        return fio;
    }

    static JsonObject buildSocketIo(Map<String, EndpointStats> agg,
                                    long readEvents, long writeEvents,
                                    long readNanos, long writeNanos,
                                    long readBytes, long writeBytes,
                                    int topN) {
        var sio = new JsonObject();
        sio.put("read_events", readEvents);
        sio.put("write_events", writeEvents);
        sio.put("total_read_time_ms", round1(toMs(readNanos)));
        sio.put("total_write_time_ms", round1(toMs(writeNanos)));
        sio.put("total_bytes_read_mb", round1(toMb(readBytes)));
        sio.put("total_bytes_written_mb", round1(toMb(writeBytes)));

        var list = new ArrayList<>(agg.entrySet());
        list.sort(Comparator.<Map.Entry<String, EndpointStats>>comparingLong(en -> en.getValue().totalNanos).reversed());

        var arr = new JsonArray();
        int rank = 1;
        for (var en : list) {
            if (rank > topN) break;
            var s = en.getValue();
            long total = s.readEvents + s.writeEvents;
            double avgMs = total == 0 ? 0.0 : toMs(s.totalNanos) / total;
            var entry = new JsonObject()
                    .put("rank", rank)
                    .put("endpoint", en.getKey())
                    .put("address", s.address == null ? JsonObject.NULL : s.address)
                    .put("read_events", s.readEvents)
                    .put("write_events", s.writeEvents)
                    .put("total_time_ms", round1(toMs(s.totalNanos)))
                    .put("total_bytes_mb", round1(toMb(s.totalBytes)))
                    .put("avg_time_ms", round1(avgMs))
                    .put("max_time_ms", round1(toMs(s.maxNanos)))
                    .put("top_call_site", dominantKey(s.callSiteCounts));
            arr.put(entry);
            rank++;
        }
        sio.put("top_endpoints_by_time", arr);
        return sio;
    }

    static JsonObject buildSignals(Map<String, FileStats> fileAgg,
                                   Map<String, EndpointStats> socketAgg,
                                   long fileTotalNanos, long socketTotalNanos,
                                   long totalIoEvents) {
        boolean significantFileIo = toMs(fileTotalNanos) > 500.0;
        boolean significantSocketIo = toMs(socketTotalNanos) > 500.0;

        String dominantEndpoint = null;
        long dominantEndpointNanos = 0;
        for (var en : socketAgg.entrySet()) {
            if (en.getValue().totalNanos > dominantEndpointNanos) {
                dominantEndpointNanos = en.getValue().totalNanos;
                dominantEndpoint = en.getKey();
            }
        }
        boolean singleEndpointDominant = socketTotalNanos > 0
                && 100.0 * dominantEndpointNanos / socketTotalNanos > 60.0;

        String repeatedFilePath = null;
        long repeatedReadEvents = 0;
        for (var en : fileAgg.entrySet()) {
            if (en.getValue().readEvents > repeatedReadEvents) {
                repeatedReadEvents = en.getValue().readEvents;
                repeatedFilePath = en.getKey();
            }
        }
        boolean repeatedFileAccess = repeatedReadEvents > 20;

        boolean sparse = totalIoEvents < 10;

        return new JsonObject()
                .put("significant_file_io", significantFileIo)
                .put("significant_socket_io", significantSocketIo)
                .put("single_endpoint_dominant", singleEndpointDominant)
                .put("dominant_endpoint", dominantEndpoint == null ? JsonObject.NULL : dominantEndpoint)
                .put("repeated_file_access", repeatedFileAccess)
                .put("repeated_file_path", repeatedFileAccess ? repeatedFilePath : JsonObject.NULL)
                .put("io_data_likely_sparse", sparse);
    }

    // Ports the tool treats as meaningful identity, not noise: well-known
    // ports (<1024, e.g. 80/443) plus the common database ports the report
    // skeleton already recognizes by number.
    static final java.util.Set<Integer> RECOGNIZED_SERVICE_PORTS = java.util.Set.of(
            5432, 3306, 1521, 27017, 6379, 9042, // Postgres, MySQL, Oracle, Mongo, Redis, Cassandra
            80, 443, 8080, 8443);

    static String endpointKey(RecordedEvent e) {
        String host = tryGetString(e, "host");
        String address = tryGetString(e, "address");
        Integer port = tryGetInt(e, "port");
        String left;
        if (host != null && !host.isEmpty()) left = host;
        else if (address != null && !address.isEmpty()) left = address;
        else left = "unknown";

        // A recognized service port is a stable, meaningful identity — keep
        // it in the key. Any other port is far more likely the ephemeral
        // remote port JFR reports for an INBOUND connection (jfrdoc's
        // primary use case is server-side Spring Boot/Quarkus apps): every
        // distinct client connection would otherwise get its own "endpoint",
        // fragmenting what is really one aggregate ("inbound traffic from
        // this host") into one entry per request. Fall back to host-only.
        if (port != null && (port < 1024 || RECOGNIZED_SERVICE_PORTS.contains(port))) {
            return left + ":" + port;
        }
        return left;
    }

    static String topFrameSignature(RecordedStackTrace trace) {
        if (trace == null) return null;
        List<RecordedFrame> frames = trace.getFrames();
        if (frames == null || frames.isEmpty()) return null;
        RecordedFrame frame = frames.get(0);
        if (!frame.isJavaFrame()) return null;
        RecordedMethod method = frame.getMethod();
        if (method == null) return null;
        RecordedClass cls = method.getType();
        if (cls == null || cls.getName() == null) return null;
        int line = frame.getLineNumber();
        return cls.getName() + "." + method.getName() + (line >= 0 ? ":" + line : "");
    }

    static Object dominantKey(Map<String, Long> counts) {
        String top = null;
        long topCount = -1;
        for (var en : counts.entrySet()) {
            if (en.getValue() > topCount) {
                topCount = en.getValue();
                top = en.getKey();
            }
        }
        return top == null ? JsonObject.NULL : top;
    }

    static long nonNegativeBytes(Long v) {
        if (v == null || v < 0) return 0L;
        return v;
    }

    static Duration tryGetDuration(RecordedEvent e, String field) {
        try {
            if (e.hasField(field)) return e.getDuration(field);
        } catch (RuntimeException ignored) {}
        return null;
    }

    static String tryGetString(RecordedEvent e, String field) {
        try {
            if (e.hasField(field)) return e.getString(field);
        } catch (RuntimeException ignored) {}
        return null;
    }

    static Long tryGetLong(RecordedEvent e, String field) {
        try {
            if (e.hasField(field)) return e.getLong(field);
        } catch (RuntimeException ignored) {}
        return null;
    }

    static Integer tryGetInt(RecordedEvent e, String field) {
        try {
            if (e.hasField(field)) return e.getInt(field);
        } catch (RuntimeException ignored) {}
        return null;
    }

    static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    static double toMs(double nanos) {
        return nanos / 1_000_000.0;
    }

    static double toMb(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static final class FileStats {
        long readEvents;
        long writeEvents;
        long totalNanos;
        long totalBytes;
        long maxNanos;
        final Map<String, Long> callSiteCounts = new LinkedHashMap<>();
    }

    static final class EndpointStats {
        long readEvents;
        long writeEvents;
        long totalNanos;
        long totalBytes;
        long maxNanos;
        String address;
        final Map<String, Long> callSiteCounts = new LinkedHashMap<>();
    }
}
