package jfrdoc.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jfrdoc.json.JsonArray;
import jfrdoc.json.JsonObject;

/**
 * Compares two .jfr recordings — a baseline and a current one — on a fixed
 * set of regression-relevant metrics (allocation rate, GC pause overhead,
 * GC p99 pause, total memory footprint, container-fit verdict) and returns
 * a PASS/FAIL verdict. This is the deterministic core of a CI regression
 * gate: no model involved, same two inputs always produce the same verdict.
 *
 * Reuses the existing tools' package-private analyze() methods directly, in
 * process — no JSON round-trip through their String output.
 */
public class JfrBaselineDiffTool implements Tool {

    static final String DEFAULT_FRAMEWORK = "other";

    // Relative (%) thresholds for quantities that scale with load; an
    // absolute (percentage-point) threshold for GC pause overhead
    // specifically, since that figure is already a percentage and typically
    // close to zero — a relative-% comparison on a near-zero baseline is
    // unstable (0.02% -> 0.05% reads as a "150% regression" that isn't
    // actually meaningful).
    // Public: jfrdoc.mcp.McpServer's `diff` CLI subcommand uses these as its
    // own flag defaults, so the MCP-tool and CLI entry points can't drift.
    public static final double DEFAULT_ALLOCATION_RATE_THRESHOLD_PCT = 20.0;
    public static final double DEFAULT_GC_PAUSE_OVERHEAD_THRESHOLD_PP = 2.0;
    public static final double DEFAULT_GC_P99_PAUSE_THRESHOLD_PCT = 30.0;
    public static final double DEFAULT_MEMORY_THRESHOLD_PCT = 15.0;

    static final List<String> VERDICT_ORDER = List.of("safe", "tight", "at_risk", "exceeded");

    @Override
    public String toolName() {
        return "jfr_baseline_diff";
    }

    @Override
    public String description() {
        return "Compares two JFR recordings of the same workload — a baseline (e.g. before a change, or a "
                + "prior release) and a current one (e.g. after a change) — on allocation rate, GC pause "
                + "overhead, GC p99 pause, total memory footprint, and container-fit verdict. Flags any metric "
                + "that regressed beyond its threshold and returns an overall PASS/FAIL verdict. This is the "
                + "deterministic core of a CI performance-regression gate: no model involved, same two inputs "
                + "always produce the same verdict. Call this to check whether a change regressed performance "
                + "between two recordings, not for analyzing a single recording (use jfr_summary and the other "
                + "tools for that).";
    }

    enum Field {
        baseline_path, current_path, container_memory_mb, framework,
        allocation_rate_threshold_pct, gc_pause_overhead_threshold_pp,
        gc_p99_pause_threshold_pct, memory_threshold_pct
    }

    @Override
    public String inputSchema() {
        return Tool.schema(
                Prop.string(Field.baseline_path,
                        "Absolute or relative filesystem path to the baseline .jfr file"),
                Prop.string(Field.current_path,
                        "Absolute or relative filesystem path to the current .jfr file to compare against the baseline"),
                Prop.integer(Field.container_memory_mb,
                        "Container memory limit in MB, applied to both recordings for container-fit comparison. "
                                + "If omitted, container-fit is not evaluable.").optional(),
                Prop.stringEnum(Field.framework,
                        "Framework hint for allocation categorization (default 'other')",
                        "spring", "quarkus", "other").optional(),
                Prop.number(Field.allocation_rate_threshold_pct,
                        "Regression threshold for allocation rate, as a % increase over baseline (default 20)")
                        .optional(),
                Prop.number(Field.gc_pause_overhead_threshold_pp,
                        "Regression threshold for GC pause overhead, as a percentage-point increase over "
                                + "baseline (default 2)").optional(),
                Prop.number(Field.gc_p99_pause_threshold_pct,
                        "Regression threshold for GC p99 pause time, as a % increase over baseline (default 30)")
                        .optional(),
                Prop.number(Field.memory_threshold_pct,
                        "Regression threshold for total committed memory, as a % increase over baseline "
                                + "(default 15)").optional()
        );
    }

    @Override
    public String execute(JsonObject input) {
        if (!input.has(Field.baseline_path.name()) || !input.has(Field.current_path.name())) {
            return "Error: Missing required parameters: baseline_path and current_path";
        }
        var baselinePath = Path.of(input.getString(Field.baseline_path.name()));
        var currentPath = Path.of(input.getString(Field.current_path.name()));
        for (var p : List.of(baselinePath, currentPath)) {
            if (!Files.exists(p)) return "Error: JFR file not found: " + p;
            if (!Files.isRegularFile(p)) return "Error: Not a regular file: " + p;
        }

        Integer containerMb = null;
        if (input.has(Field.container_memory_mb.name())
                && !JsonObject.NULL.equals(input.get(Field.container_memory_mb.name()))) {
            try {
                int v = input.getInt(Field.container_memory_mb.name());
                if (v > 0) containerMb = v;
            } catch (RuntimeException ignored) {}
        }

        String framework = DEFAULT_FRAMEWORK;
        if (input.has(Field.framework.name())) {
            String raw = input.getString(Field.framework.name());
            if (raw != null && !raw.isEmpty()) framework = raw;
        }

        double allocThresholdPct = optionalDouble(input, Field.allocation_rate_threshold_pct,
                DEFAULT_ALLOCATION_RATE_THRESHOLD_PCT);
        double gcOverheadThresholdPp = optionalDouble(input, Field.gc_pause_overhead_threshold_pp,
                DEFAULT_GC_PAUSE_OVERHEAD_THRESHOLD_PP);
        double gcP99ThresholdPct = optionalDouble(input, Field.gc_p99_pause_threshold_pct,
                DEFAULT_GC_P99_PAUSE_THRESHOLD_PCT);
        double memoryThresholdPct = optionalDouble(input, Field.memory_threshold_pct,
                DEFAULT_MEMORY_THRESHOLD_PCT);

        FrameworkCategorizer categorizer;
        try {
            categorizer = FrameworkCategorizer.forFramework(framework);
        } catch (IOException e) {
            return "Error: Could not load categorization rules (" + e.getClass().getSimpleName() + ")";
        }

        try {
            return compare(baselinePath, currentPath, containerMb, framework, categorizer,
                    allocThresholdPct, gcOverheadThresholdPp, gcP99ThresholdPct, memoryThresholdPct)
                    .toString(2);
        } catch (IOException e) {
            return "Error: Could not read JFR file (" + e.getClass().getSimpleName() + ")";
        }
    }

    /**
     * Public so the plugin's headless CI mode (jfrdoc.mcp.McpServer's `diff`
     * subcommand) can get the verdict as a structured JsonObject instead of
     * re-parsing this tool's own JSON string output.
     */
    public static JsonObject compare(Path baselinePath, Path currentPath, Integer containerMb, String framework,
                                      FrameworkCategorizer categorizer, double allocThresholdPct,
                                      double gcOverheadThresholdPp, double gcP99ThresholdPct,
                                      double memoryThresholdPct) throws IOException {

        JsonObject baselineAlloc = JfrAllocationTool.analyze(
                baselinePath, JfrAllocationTool.DEFAULT_TOP_N, framework, categorizer);
        JsonObject currentAlloc = JfrAllocationTool.analyze(
                currentPath, JfrAllocationTool.DEFAULT_TOP_N, framework, categorizer);
        JsonObject baselineGc = JfrGcStatsTool.analyze(baselinePath);
        JsonObject currentGc = JfrGcStatsTool.analyze(currentPath);
        JsonObject baselineMem = JfrMemoryTool.analyze(baselinePath, containerMb, JfrMemoryTool.DEFAULT_STACK_KB);
        JsonObject currentMem = JfrMemoryTool.analyze(currentPath, containerMb, JfrMemoryTool.DEFAULT_STACK_KB);

        var metrics = new ArrayList<MetricResult>();

        metrics.add(relativeIncreaseMetric(
                "allocation_rate_mb_per_s", "mb_per_second",
                numOrNull(nested(baselineAlloc, "estimated_allocation_rate", "mb_per_second")),
                numOrNull(nested(currentAlloc, "estimated_allocation_rate", "mb_per_second")),
                allocThresholdPct));

        metrics.add(pointIncreaseMetric(
                "gc_pause_overhead_pct", "pct",
                numOrNull(nested(baselineGc, "summary", "pause_overhead_pct")),
                numOrNull(nested(currentGc, "summary", "pause_overhead_pct")),
                gcOverheadThresholdPp));

        metrics.add(relativeIncreaseMetric(
                "gc_p99_pause_ms", "ms",
                numOrNull(nested(baselineGc, "summary", "p99_pause_ms")),
                numOrNull(nested(currentGc, "summary", "p99_pause_ms")),
                gcP99ThresholdPct));

        // total_committed_mb is only meaningful when NMT was available on both
        // sides — independent of whether a container limit was supplied, so
        // this checks nmt.available rather than container_fit.evaluable.
        boolean baselineNmt = isTrue(nested(baselineMem, "nmt", "available"));
        boolean currentNmt = isTrue(nested(currentMem, "nmt", "available"));
        Double baselineMemMb = baselineNmt && currentNmt
                ? numOrNull(nested(baselineMem, "native_memory_total", "committed_mb")) : null;
        Double currentMemMb = baselineNmt && currentNmt
                ? numOrNull(nested(currentMem, "native_memory_total", "committed_mb")) : null;
        metrics.add(relativeIncreaseMetric(
                "total_committed_memory_mb", "mb", baselineMemMb, currentMemMb, memoryThresholdPct));

        ContainerFitResult containerFit = containerFitComparison(baselineMem, currentMem);

        boolean anyRegressed = containerFit.downgraded();
        var regressions = new ArrayList<String>();
        for (MetricResult m : metrics) {
            if (m.regressed()) {
                anyRegressed = true;
                regressions.add(m.name());
            }
        }
        if (containerFit.downgraded()) regressions.add("container_fit");

        var metricsArr = new JsonArray();
        for (MetricResult m : metrics) metricsArr.put(m.toJson());

        var regressionsArr = new JsonArray();
        regressions.forEach(regressionsArr::put);

        var result = new JsonObject();
        result.put("baseline", recordingRef(baselinePath, baselineAlloc));
        result.put("current", recordingRef(currentPath, currentAlloc));
        result.put("framework_used_for_categorization", framework);
        result.put("metrics", metricsArr);
        result.put("container_fit", containerFit.toJson());
        result.put("verdict", anyRegressed ? "FAIL" : "PASS");
        result.put("regressions", regressionsArr);
        return result;
    }

    static JsonObject recordingRef(Path path, JsonObject analyzed) {
        var ref = new JsonObject();
        ref.put("path", path.toString());
        Object rec = analyzed.get("recording");
        if (rec instanceof JsonObject ro && ro.has("duration_seconds")) {
            ref.put("duration_seconds", ro.get("duration_seconds"));
        }
        return ref;
    }

    static ContainerFitResult containerFitComparison(JsonObject baselineMem, JsonObject currentMem) {
        String baselineVerdict = strOrNull(nested(baselineMem, "container_fit", "verdict"));
        String currentVerdict = strOrNull(nested(currentMem, "container_fit", "verdict"));
        boolean evaluable = baselineVerdict != null && currentVerdict != null
                && VERDICT_ORDER.contains(baselineVerdict) && VERDICT_ORDER.contains(currentVerdict);
        boolean downgraded = evaluable
                && VERDICT_ORDER.indexOf(currentVerdict) > VERDICT_ORDER.indexOf(baselineVerdict);
        return new ContainerFitResult(baselineVerdict, currentVerdict, evaluable, downgraded);
    }

    record ContainerFitResult(String baselineVerdict, String currentVerdict, boolean evaluable, boolean downgraded) {
        JsonObject toJson() {
            var out = new JsonObject();
            out.put("baseline_verdict", baselineVerdict == null ? JsonObject.NULL : baselineVerdict);
            out.put("current_verdict", currentVerdict == null ? JsonObject.NULL : currentVerdict);
            out.put("evaluable", evaluable);
            out.put("downgraded", downgraded);
            return out;
        }
    }

    record MetricResult(String name, String unit, String comparison, Double baselineValue, Double currentValue,
                         Double delta, double threshold, boolean evaluable, boolean regressed, String note) {
        JsonObject toJson() {
            var m = new JsonObject();
            m.put("name", name);
            m.put("unit", unit);
            m.put("comparison", comparison);
            m.put("baseline_value", baselineValue == null ? JsonObject.NULL : baselineValue);
            m.put("current_value", currentValue == null ? JsonObject.NULL : currentValue);
            m.put("delta", delta == null ? JsonObject.NULL : delta);
            m.put("threshold", threshold);
            m.put("evaluable", evaluable);
            m.put("regressed", regressed);
            m.put("note", note == null ? JsonObject.NULL : note);
            return m;
        }
    }

    static MetricResult relativeIncreaseMetric(String name, String unit, Double baseline, Double current,
                                                double thresholdPct) {
        if (baseline == null || current == null) {
            return new MetricResult(name, unit, "relative_pct_increase", roundOrNull(baseline), roundOrNull(current),
                    null, thresholdPct, false, false,
                    "Not evaluable — missing data in baseline and/or current recording.");
        }
        // A near-zero baseline makes a relative % undefined/unstable (any small
        // absolute change reads as a huge or infinite %), so a new, non-zero
        // signal appearing where the baseline had ~none is reported directly
        // rather than as a relative delta.
        if (baseline < 1e-6) {
            boolean newSignal = current >= 1e-6;
            return new MetricResult(name, unit, "relative_pct_increase", round2(baseline), round2(current),
                    null, thresholdPct, true, newSignal,
                    newSignal ? "Baseline was ~0; current recording shows a new, non-zero value."
                            : "Both baseline and current are ~0 — no regression.");
        }
        double deltaPct = 100.0 * (current - baseline) / baseline;
        return new MetricResult(name, unit, "relative_pct_increase", round2(baseline), round2(current),
                round2(deltaPct), thresholdPct, true, deltaPct > thresholdPct, null);
    }

    /** Same shape, but delta/threshold are absolute percentage-point differences, not a relative %. */
    static MetricResult pointIncreaseMetric(String name, String unit, Double baseline, Double current,
                                             double thresholdPp) {
        if (baseline == null || current == null) {
            return new MetricResult(name, unit, "absolute_point_increase", roundOrNull(baseline),
                    roundOrNull(current), null, thresholdPp, false, false,
                    "Not evaluable — missing data in baseline and/or current recording.");
        }
        double delta = current - baseline;
        return new MetricResult(name, unit, "absolute_point_increase", round2(baseline), round2(current),
                round2(delta), thresholdPp, true, delta > thresholdPp, null);
    }

    static Object nested(JsonObject root, String... keys) {
        Object current = root;
        for (String k : keys) {
            if (!(current instanceof JsonObject jo) || !jo.has(k)) return null;
            current = jo.get(k);
        }
        return current;
    }

    static Double numOrNull(Object o) {
        return (o == null || o == JsonObject.NULL || !(o instanceof Number n)) ? null : n.doubleValue();
    }

    static String strOrNull(Object o) {
        return (o == null || o == JsonObject.NULL || !(o instanceof String s)) ? null : s;
    }

    static boolean isTrue(Object o) {
        return o instanceof Boolean b && b;
    }

    static double optionalDouble(JsonObject input, Field field, double fallback) {
        if (!input.has(field.name()) || JsonObject.NULL.equals(input.get(field.name()))) return fallback;
        try {
            return input.getDouble(field.name());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static Double roundOrNull(Double v) { return v == null ? null : round2(v); }

    static Double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
