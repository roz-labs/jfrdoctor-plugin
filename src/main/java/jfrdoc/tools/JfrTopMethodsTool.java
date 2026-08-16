package jfrdoc.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import jfrdoc.json.JsonArray;
import jfrdoc.json.JsonObject;

public class JfrTopMethodsTool implements Tool {

    static final int DEFAULT_TOP_N = 20;
    static final int MIN_TOP_N = 1;
    static final int MAX_TOP_N = 100;
    static final String DEFAULT_FRAMEWORK = "other";

    @Override
    public String toolName() {
        return "jfr_top_methods";
    }

    @Override
    public String description() {
        return "Aggregates jdk.ExecutionSample events from a JFR file to identify on-CPU Java hotspots. "
                + "Returns the top N methods by sample count with category (user_code / framework / jdk), "
                + "dominant caller, and category breakdown over attributed samples. "
                + "Use this to answer 'where is on-CPU Java time being spent?' "
                + "Scope: on-CPU Java only. Native CPU time and blocked-in-native time (jdk.NativeMethodSample) are out of scope. "
                + "Also returns a sample_quality block (separate from attribution categories) reporting how many samples could not be resolved to a Java method and why "
                + "(no_stack_trace, native_top_frame, unknown_method_or_class). A healthy recording has unattributed_pct near zero; "
                + "a materially non-zero value indicates instrumentation gaps, not where CPU is being spent.";
    }

    enum Field { path, top_n, framework }

    @Override
    public String inputSchema() {
        return Tool.schema(
                Prop.string(Field.path,
                        "Absolute or relative filesystem path to the .jfr file"),
                Prop.integer(Field.top_n,
                        "Number of top methods to return (clamped to [1,100]; default 20)").optional(),
                Prop.stringEnum(Field.framework,
                        "Framework hint for categorization (default 'other')",
                        "spring", "quarkus", "other").optional()
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

        String framework = DEFAULT_FRAMEWORK;
        if (input.has(Field.framework.name())) {
            String raw = input.getString(Field.framework.name());
            if (raw != null && !raw.isEmpty()) framework = raw;
        }

        FrameworkCategorizer categorizer;
        try {
            categorizer = FrameworkCategorizer.forFramework(framework);
        } catch (IOException e) {
            return "Error: Could not load categorization rules: " + e.getMessage();
        }

        try {
            return analyze(path, topN, framework, categorizer).toString(2);
        } catch (IOException e) {
            return "Error: Could not read JFR file: " + e.getMessage();
        }
    }

    static JsonObject analyze(Path path, int topN, String framework, FrameworkCategorizer categorizer) throws IOException {

        var methodAgg = new LinkedHashMap<String, MethodStats>();
        var categoryCounts = new HashMap<String, Long>();
        for (String c : List.of("user_code", "framework", "jdk")) {
            categoryCounts.put(c, 0L);
        }

        long totalSamples = 0;
        long noStackTrace = 0;
        long nativeTopFrame = 0;
        long unknownMethodOrClass = 0;
        Instant earliest = null;
        Instant latest = null;

        try (var rf = new RecordingFile(path)) {
            while (rf.hasMoreEvents()) {
                var e = rf.readEvent();

                // Recording span must come from every event, not just
                // jdk.ExecutionSample — otherwise duration_seconds reflects
                // only the window samples happened to occur in, not the
                // actual recording length.
                var ts = e.getStartTime();
                if (earliest == null || ts.isBefore(earliest)) earliest = ts;
                if (latest == null || ts.isAfter(latest)) latest = ts;

                if (!e.getEventType().getName().equals("jdk.ExecutionSample")) continue;

                totalSamples++;

                RecordedStackTrace trace = e.getStackTrace();
                if (trace == null || trace.getFrames().isEmpty()) {
                    noStackTrace++;
                    continue;
                }
                var frames = trace.getFrames();

                RecordedFrame top = frames.get(0);
                if (!top.isJavaFrame()) {
                    nativeTopFrame++;
                    continue;
                }
                RecordedMethod method = top.getMethod();
                if (method == null) {
                    unknownMethodOrClass++;
                    continue;
                }
                RecordedClass cls = method.getType();
                if (cls == null || cls.getName() == null) {
                    unknownMethodOrClass++;
                    continue;
                }

                String fqcn = cls.getName();
                String methodName = method.getName();
                int line = top.getLineNumber();
                String methodKey = fqcn + "." + methodName + (line >= 0 ? ":" + line : "");
                String category = categorizer.categorize(fqcn);

                categoryCounts.merge(category, 1L, Long::sum);
                var stats = methodAgg.computeIfAbsent(methodKey, k -> new MethodStats(category));
                stats.samples++;

                String callerSig = callerSignature(frames);
                if (callerSig != null) {
                    stats.callerCounts.merge(callerSig, 1L, Long::sum);
                }
            }
        }

        long unattributed = noStackTrace + nativeTopFrame + unknownMethodOrClass;
        long attributed = totalSamples - unattributed;

        var result = new JsonObject();

        var recording = new JsonObject();
        recording.put("path", path.toAbsolutePath().toString());
        if (earliest != null && latest != null) {
            recording.put("duration_seconds",
                    round1(Duration.between(earliest, latest).toMillis() / 1000.0));
        } else {
            recording.put("duration_seconds", 0.0);
        }
        result.put("recording", recording);

        result.put("execution_samples", new JsonObject().put("total", totalSamples));

        var quality = new JsonObject();
        quality.put("attributed_samples", attributed);
        quality.put("unattributed_samples", unattributed);
        quality.put("unattributed_pct", totalSamples == 0 ? 0.0 : round1(100.0 * unattributed / totalSamples));
        var breakdown = new JsonObject();
        breakdown.put("no_stack_trace", noStackTrace);
        breakdown.put("native_top_frame", nativeTopFrame);
        breakdown.put("unknown_method_or_class", unknownMethodOrClass);
        quality.put("breakdown", breakdown);
        result.put("sample_quality", quality);

        var categories = new JsonObject();
        for (String c : List.of("user_code", "framework", "jdk")) {
            long count = categoryCounts.getOrDefault(c, 0L);
            var co = new JsonObject();
            co.put("samples", count);
            co.put("pct", attributed == 0 ? 0.0 : round1(100.0 * count / attributed));
            categories.put(c, co);
        }
        result.put("categories", categories);

        var sorted = new ArrayList<>(methodAgg.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, MethodStats>>comparingLong(en -> en.getValue().samples).reversed());

        var topMethods = new JsonArray();
        int rank = 1;
        for (var en : sorted) {
            if (rank > topN) break;
            var stats = en.getValue();
            var entry = new JsonObject();
            entry.put("rank", rank);
            entry.put("method", en.getKey());
            entry.put("category", stats.category);
            entry.put("samples", stats.samples);
            entry.put("pct_of_total", totalSamples == 0 ? 0.0 : round1(100.0 * stats.samples / totalSamples));

            String topCaller = null;
            long topCallerCount = 0;
            for (var ce : stats.callerCounts.entrySet()) {
                if (ce.getValue() > topCallerCount) {
                    topCallerCount = ce.getValue();
                    topCaller = ce.getKey();
                }
            }
            if (topCaller != null) {
                entry.put("top_caller", topCaller);
                entry.put("top_caller_share_pct",
                        stats.samples == 0 ? 0.0 : round1(100.0 * topCallerCount / stats.samples));
            } else {
                entry.put("top_caller", JsonObject.NULL);
                entry.put("top_caller_share_pct", 0.0);
            }

            topMethods.put(entry);
            rank++;
        }
        result.put("top_methods", topMethods);

        result.put("framework_used_for_categorization", framework);
        return result;
    }

    static String callerSignature(List<RecordedFrame> frames) {
        if (frames.size() < 2) return null;
        RecordedFrame caller = frames.get(1);
        if (!caller.isJavaFrame()) return null;
        var cm = caller.getMethod();
        if (cm == null) return null;
        var ct = cm.getType();
        if (ct == null || ct.getName() == null) return null;
        return ct.getName() + "." + cm.getName();
    }

    static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static final class MethodStats {
        final String category;
        long samples;
        final Map<String, Long> callerCounts = new HashMap<>();

        MethodStats(String category) {
            this.category = category;
        }
    }
}
