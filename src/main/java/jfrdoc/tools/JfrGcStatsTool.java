package jfrdoc.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;
import jfrdoc.json.JsonArray;
import jfrdoc.json.JsonObject;

public class JfrGcStatsTool implements Tool {

    @Override
    public String toolName() {
        return "jfr_gc_stats";
    }

    @Override
    public String description() {
        return "Analyzes garbage collection behavior from JFR events. "
                + "Returns: collector configuration, GC count and frequency, "
                + "pause time distribution (p50/p95/p99/max), total pause overhead as % of recording duration, "
                + "breakdown by collector name and cause, heap occupancy range, and anomalies "
                + "(full GCs, evacuation failures, long pauses). "
                + "Call this when summary.notable_events_present.gc is true.";
    }

    enum Field { path }

    @Override
    public String inputSchema() {
        return Tool.schema(Prop.string(Field.path,
                "Absolute or relative filesystem path to the .jfr file"));
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
        try {
            return analyze(path).toString(2);
        } catch (IOException e) {
            return "Error: Could not read JFR file (" + e.getClass().getSimpleName() + ")";
        }
    }

    static JsonObject analyze(Path path) throws IOException {
        var pauseNanos = new ArrayList<Long>();
        var byName = new HashMap<String, NameStats>();
        var byCause = new HashMap<String, Long>();

        long fullGcs = 0;
        long longOver100 = 0;
        long longOver500 = 0;
        long explicitSystemGcs = 0;
        long humongousAllocations = 0;
        long evacuationFailures = 0;

        long heapSnapshots = 0;
        long minHeapUsed = Long.MAX_VALUE;
        long maxHeapUsed = Long.MIN_VALUE;
        long sumHeapAfterGc = 0;
        long countHeapAfterGc = 0;
        long lastCommittedSize = 0;
        double maxUsedPctOfCommitted = 0.0;

        JsonObject configuration = null;
        Instant earliest = null;
        Instant latest = null;

        try (var rf = new RecordingFile(path)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String type = e.getEventType().getName();

                var ts = e.getStartTime();
                if (earliest == null || ts.isBefore(earliest)) earliest = ts;
                if (latest == null || ts.isAfter(latest)) latest = ts;

                switch (type) {
                    case "jdk.GarbageCollection" -> {
                        String name = tryGetString(e, "name");
                        String cause = tryGetString(e, "cause");
                        Duration sumOfPauses = tryGetDuration(e, "sumOfPauses");
                        Duration longest = tryGetDuration(e, "longestPause");

                        long pauseNs = sumOfPauses == null ? 0L : sumOfPauses.toNanos();
                        long longestNs = longest == null ? 0L : longest.toNanos();
                        pauseNanos.add(pauseNs);

                        if (name != null) {
                            var stats = byName.computeIfAbsent(name, k -> new NameStats());
                            stats.count++;
                            stats.totalPauseNanos += pauseNs;
                            if (longestNs > stats.maxPauseNanos) stats.maxPauseNanos = longestNs;
                            if (pauseNs > stats.maxPauseNanos) stats.maxPauseNanos = pauseNs;
                        }
                        if (cause != null && !cause.isEmpty()) {
                            byCause.merge(cause, 1L, Long::sum);
                        }

                        if (isFullGc(name, cause)) fullGcs++;
                        double pauseMs = pauseNs / 1_000_000.0;
                        if (pauseMs >= 100.0) longOver100++;
                        if (pauseMs >= 500.0) longOver500++;
                        if (cause != null && cause.contains("System.gc")) explicitSystemGcs++;
                        if (cause != null && cause.contains("Humongous")) humongousAllocations++;
                    }
                    case "jdk.GCConfiguration" -> {
                        if (configuration == null) configuration = readConfiguration(e);
                    }
                    case "jdk.EvacuationFailed" -> evacuationFailures++;
                    case "jdk.GCHeapSummary" -> {
                        Long heapUsed = tryGetLong(e, "heapUsed");
                        Long committedSize = readCommittedSize(e);
                        String when = tryGetString(e, "when");

                        if (heapUsed != null) {
                            heapSnapshots++;
                            if (heapUsed < minHeapUsed) minHeapUsed = heapUsed;
                            if (heapUsed > maxHeapUsed) maxHeapUsed = heapUsed;
                            if ("After GC".equals(when)) {
                                sumHeapAfterGc += heapUsed;
                                countHeapAfterGc++;
                            }
                            if (committedSize != null && committedSize > 0) {
                                lastCommittedSize = committedSize;
                                double pct = 100.0 * heapUsed / committedSize;
                                if (pct > maxUsedPctOfCommitted) maxUsedPctOfCommitted = pct;
                            }
                        }
                    }
                    default -> {}
                }
            }
        }

        double durationSeconds = 0.0;
        if (earliest != null && latest != null) {
            durationSeconds = Duration.between(earliest, latest).toMillis() / 1000.0;
        }

        var result = new JsonObject();

        var recording = new JsonObject();
        recording.put("path", path.toString());
        recording.put("duration_seconds", round1(durationSeconds));
        result.put("recording", recording);

        result.put("configuration", configuration == null ? nullConfiguration() : configuration);

        long totalGcs = pauseNanos.size();
        long totalPauseNanos = pauseNanos.stream().mapToLong(Long::longValue).sum();
        double totalPauseMs = totalPauseNanos / 1_000_000.0;
        double avgPauseMs = totalGcs == 0 ? 0.0 : totalPauseMs / totalGcs;
        double pauseOverheadPct = durationSeconds <= 0
                ? 0.0
                : totalPauseMs / (durationSeconds * 1000.0) * 100.0;
        double gcsPerMinute = durationSeconds <= 0 ? 0.0 : totalGcs / durationSeconds * 60.0;

        Collections.sort(pauseNanos);
        double p50 = percentileMs(pauseNanos, 50);
        double p95 = percentileMs(pauseNanos, 95);
        double p99 = percentileMs(pauseNanos, 99);
        double maxPauseMs = pauseNanos.isEmpty()
                ? 0.0
                : pauseNanos.get(pauseNanos.size() - 1) / 1_000_000.0;

        var summary = new JsonObject();
        summary.put("total_gcs", totalGcs);
        summary.put("total_pause_time_ms", round2(totalPauseMs));
        summary.put("pause_overhead_pct", round2(pauseOverheadPct));
        summary.put("gcs_per_minute", round1(gcsPerMinute));
        summary.put("avg_pause_ms", round2(avgPauseMs));
        summary.put("p50_pause_ms", round2(p50));
        summary.put("p95_pause_ms", round2(p95));
        summary.put("p99_pause_ms", round2(p99));
        summary.put("max_pause_ms", round2(maxPauseMs));
        result.put("summary", summary);

        result.put("by_name", topByName(byName, 10));
        result.put("by_cause", topByCause(byCause, 10));

        var heap = new JsonObject();
        heap.put("snapshots", heapSnapshots);
        if (heapSnapshots > 0) {
            heap.put("min_used_mb", round1(toMb(minHeapUsed)));
            heap.put("max_used_mb", round1(toMb(maxHeapUsed)));
            heap.put("avg_used_after_gc_mb", countHeapAfterGc == 0
                    ? 0.0
                    : round1(toMb(sumHeapAfterGc / (double) countHeapAfterGc)));
            heap.put("committed_size_mb", round1(toMb(lastCommittedSize)));
            heap.put("max_used_pct_of_committed", round1(maxUsedPctOfCommitted));
        } else {
            heap.put("min_used_mb", 0.0);
            heap.put("max_used_mb", 0.0);
            heap.put("avg_used_after_gc_mb", 0.0);
            heap.put("committed_size_mb", 0.0);
            heap.put("max_used_pct_of_committed", 0.0);
        }
        result.put("heap", heap);

        var anomalies = new JsonObject();
        anomalies.put("full_gcs", fullGcs);
        anomalies.put("long_pauses_over_100ms", longOver100);
        anomalies.put("long_pauses_over_500ms", longOver500);
        anomalies.put("explicit_system_gcs", explicitSystemGcs);
        anomalies.put("humongous_allocations", humongousAllocations);
        anomalies.put("evacuation_failures", evacuationFailures);
        result.put("anomalies", anomalies);

        return result;
    }

    static JsonObject readConfiguration(RecordedEvent e) {
        var c = new JsonObject();
        c.put("young_collector", orNull(tryGetString(e, "youngCollector")));
        c.put("old_collector", orNull(tryGetString(e, "oldCollector")));
        c.put("parallel_gc_threads", orNullInt(tryGetInt(e, "parallelGCThreads")));
        c.put("concurrent_gc_threads", orNullInt(tryGetInt(e, "concurrentGCThreads")));
        c.put("uses_dynamic_gc_threads", orNullBool(tryGetBoolean(e, "usesDynamicGCThreads")));
        c.put("pause_target_ms", pauseTargetMs(e));
        c.put("gc_time_ratio", orNullInt(tryGetInt(e, "gcTimeRatio")));
        return c;
    }

    static Object pauseTargetMs(RecordedEvent e) {
        Duration target = tryGetDuration(e, "pauseTarget");
        if (target == null) return JsonObject.NULL;
        long seconds = target.getSeconds();
        if (seconds <= 0 || seconds > 86_400) return JsonObject.NULL;
        try {
            return target.toMillis();
        } catch (ArithmeticException overflow) {
            return JsonObject.NULL;
        }
    }

    static JsonObject nullConfiguration() {
        var c = new JsonObject();
        c.put("young_collector", JsonObject.NULL);
        c.put("old_collector", JsonObject.NULL);
        c.put("parallel_gc_threads", JsonObject.NULL);
        c.put("concurrent_gc_threads", JsonObject.NULL);
        c.put("uses_dynamic_gc_threads", JsonObject.NULL);
        c.put("pause_target_ms", JsonObject.NULL);
        c.put("gc_time_ratio", JsonObject.NULL);
        return c;
    }

    static JsonArray topByName(Map<String, NameStats> byName, int limit) {
        var list = new ArrayList<>(byName.entrySet());
        list.sort(Comparator.<Map.Entry<String, NameStats>>comparingLong(en -> en.getValue().count).reversed());
        var out = new JsonArray();
        int i = 0;
        for (var en : list) {
            if (i++ >= limit) break;
            var s = en.getValue();
            double totalMs = s.totalPauseNanos / 1_000_000.0;
            double avgMs = s.count == 0 ? 0.0 : totalMs / s.count;
            double maxMs = s.maxPauseNanos / 1_000_000.0;
            out.put(new JsonObject()
                    .put("name", en.getKey())
                    .put("count", s.count)
                    .put("total_pause_ms", round2(totalMs))
                    .put("avg_pause_ms", round2(avgMs))
                    .put("max_pause_ms", round2(maxMs)));
        }
        return out;
    }

    static JsonArray topByCause(Map<String, Long> byCause, int limit) {
        var list = new ArrayList<>(byCause.entrySet());
        list.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        var out = new JsonArray();
        int i = 0;
        for (var en : list) {
            if (i++ >= limit) break;
            out.put(new JsonObject()
                    .put("cause", en.getKey())
                    .put("count", en.getValue()));
        }
        return out;
    }

    static double percentileMs(List<Long> sortedNanos, int p) {
        int n = sortedNanos.size();
        if (n == 0) return 0.0;
        int idx = (int) Math.ceil(p * n / 100.0) - 1;
        if (idx < 0) idx = 0;
        if (idx >= n) idx = n - 1;
        return sortedNanos.get(idx) / 1_000_000.0;
    }

    static boolean isFullGc(String name, String cause) {
        if (name == null) return false;
        if (name.equals("G1Full")) return true;
        if (name.equals("Full GC")) return true;
        if (name.contains("Full")) return true;
        if (cause != null && cause.contains("Allocation Failure")) {
            return name.equals("G1Old")
                    || name.equals("ConcurrentMarkSweep")
                    || name.equals("ParallelOld");
        }
        return false;
    }

    static Long readCommittedSize(RecordedEvent e) {
        try {
            if (!e.hasField("heapSpace")) return null;
            Object v = e.getValue("heapSpace");
            if (v instanceof RecordedObject ro) {
                if (ro.hasField("committedSize")) {
                    return ro.getLong("committedSize");
                }
            }
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

    static Boolean tryGetBoolean(RecordedEvent e, String field) {
        try {
            if (e.hasField(field)) return e.getBoolean(field);
        } catch (RuntimeException ignored) {}
        return null;
    }

    static Duration tryGetDuration(RecordedEvent e, String field) {
        try {
            if (e.hasField(field)) return e.getDuration(field);
        } catch (RuntimeException ignored) {}
        return null;
    }

    static Object orNull(String v) { return v == null ? JsonObject.NULL : v; }
    static Object orNullInt(Integer v) { return v == null ? JsonObject.NULL : v; }
    static Object orNullBool(Boolean v) { return v == null ? JsonObject.NULL : v; }

    static double toMb(double bytes) { return bytes / (1024.0 * 1024.0); }

    static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    static final class NameStats {
        long count;
        long totalPauseNanos;
        long maxPauseNanos;
    }
}
