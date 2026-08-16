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

public class JfrNativeMethodsTool implements Tool {

    static final int DEFAULT_TOP_N = 20;
    static final int MIN_TOP_N = 1;
    static final int MAX_TOP_N = 100;
    static final String DEFAULT_FRAMEWORK = "other";

    // Wait-pattern substrings: a top native method whose name contains any of these
    // is considered a blocked-in-syscall / wait frame, not on-CPU native work.
    static final List<String> WAIT_PATTERNS = List.of(
            "accept", "wait", "poll", "epoll", "select", "park"
    );

    @Override
    public String toolName() {
        return "jfr_native_methods";
    }

    @Override
    public String description() {
        return "Aggregates jdk.NativeMethodSample events to identify where threads spend time in JVM native execution "
                + "(syscalls, JNI). Most samples here represent blocked-in-native / wait time, NOT on-CPU work "
                + "(e.g., acceptor threads in sun.nio.ch.Net.accept, event loops in sun.nio.ch.EPoll.wait). "
                + "For on-CPU hotspots, use jfr_top_methods instead. "
                + "The caller frame is usually more informative than the native method itself.";
    }

    enum Field { path, top_n, framework }

    @Override
    public String inputSchema() {
        return Tool.schema(
                Prop.string(Field.path,
                        "Absolute or relative filesystem path to the .jfr file"),
                Prop.integer(Field.top_n,
                        "Number of top native methods to return (clamped to [1,100]; default 20)").optional(),
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
        long unknownMethodOrClass = 0;
        Instant earliest = null;
        Instant latest = null;

        try (var rf = new RecordingFile(path)) {
            while (rf.hasMoreEvents()) {
                var e = rf.readEvent();

                // Recording span must come from every event, not just
                // jdk.NativeMethodSample — otherwise duration_seconds
                // reflects only the window native samples happened to occur
                // in, not the actual recording length.
                var ts = e.getStartTime();
                if (earliest == null || ts.isBefore(earliest)) earliest = ts;
                if (latest == null || ts.isAfter(latest)) latest = ts;

                if (!"jdk.NativeMethodSample".equals(e.getEventType().getName())) continue;

                totalSamples++;

                RecordedStackTrace trace = e.getStackTrace();
                if (trace == null || trace.getFrames().isEmpty()) {
                    noStackTrace++;
                    continue;
                }
                var frames = trace.getFrames();

                RecordedFrame top = frames.get(0);
                // NOTE: do NOT skip non-Java top frames here — the top frame is Native by definition
                // for jdk.NativeMethodSample events.
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
                var stats = methodAgg.computeIfAbsent(methodKey,
                        k -> new MethodStats(category, methodName));
                stats.samples++;

                String callerSig = callerSignature(frames);
                if (callerSig != null) {
                    stats.callerCounts.merge(callerSig, 1L, Long::sum);
                }
            }
        }

        long unattributed = noStackTrace + unknownMethodOrClass;
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

        result.put("native_samples", new JsonObject().put("total", totalSamples));

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
        long waitSamples = 0;
        boolean likelyOnCpuNativePresent = false;
        String dominantNativeMethod = null;
        for (var en : sorted) {
            var stats = en.getValue();
            boolean isWait = isWaitMethod(stats.methodName);
            if (isWait) waitSamples += stats.samples;

            double pctOfTotal = totalSamples == 0 ? 0.0 : round1(100.0 * stats.samples / totalSamples);
            if (!isWait && pctOfTotal > 5.0) likelyOnCpuNativePresent = true;
            if (rank == 1) dominantNativeMethod = en.getKey();

            if (rank > topN) { rank++; continue; }

            var entry = new JsonObject();
            entry.put("rank", rank);
            entry.put("method", en.getKey());
            entry.put("category", stats.category);
            entry.put("samples", stats.samples);
            entry.put("pct_of_total", pctOfTotal);

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
        result.put("top_native_methods", topMethods);

        var quality = new JsonObject();
        quality.put("attributed_samples", attributed);
        quality.put("unattributed_samples", unattributed);
        quality.put("unattributed_pct", totalSamples == 0 ? 0.0 : round1(100.0 * unattributed / totalSamples));
        var breakdown = new JsonObject();
        breakdown.put("no_stack_trace", noStackTrace);
        breakdown.put("unknown_method_or_class", unknownMethodOrClass);
        quality.put("breakdown", breakdown);
        result.put("sample_quality", quality);

        double waitFramePct = totalSamples == 0 ? 0.0 : round1(100.0 * waitSamples / totalSamples);
        var signals = new JsonObject();
        signals.put("dominated_by_wait_frames", waitFramePct > 70.0);
        signals.put("wait_frame_pct", waitFramePct);
        signals.put("likely_on_cpu_native_present", likelyOnCpuNativePresent);
        if (dominantNativeMethod != null) {
            signals.put("dominant_native_method", dominantNativeMethod);
        } else {
            signals.put("dominant_native_method", JsonObject.NULL);
        }
        result.put("signals", signals);

        result.put("framework_used_for_categorization", framework);
        return result;
    }

    static boolean isWaitMethod(String methodName) {
        if (methodName == null) return false;
        String lower = methodName.toLowerCase();
        for (String p : WAIT_PATTERNS) {
            if (lower.contains(p)) return true;
        }
        return false;
    }

    static String callerSignature(List<RecordedFrame> frames) {
        if (frames.size() < 2) return null;
        RecordedFrame caller = frames.get(1);
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
        final String methodName;
        long samples;
        final Map<String, Long> callerCounts = new HashMap<>();

        MethodStats(String category, String methodName) {
            this.category = category;
            this.methodName = methodName;
        }
    }
}
