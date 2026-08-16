package jfrdoc.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;
import jfrdoc.json.JsonArray;
import jfrdoc.json.JsonObject;

public class JfrMemoryTool implements Tool {

    static final int DEFAULT_STACK_KB = 1024;
    static final long ONE_GB_BYTES = 1024L * 1024L * 1024L;

    @Override
    public String toolName() {
        return "jfr_memory";
    }

    @Override
    public String description() {
        return "Analyzes total JVM memory footprint beyond just heap: metaspace, code cache, thread stacks, "
                + "and native memory by category (when NMT is enabled). Computes container_fit verdict "
                + "comparing total committed memory to the provided container memory limit. Critical for "
                + "diagnosing pod OOMKills where heap alone looks fine but total RSS exceeds the limit. "
                + "Call this ALWAYS — it degrades gracefully when NMT is unavailable.";
    }

    enum Field { path, container_memory_mb, default_stack_size_kb }

    @Override
    public String inputSchema() {
        return Tool.schema(
                Prop.string(Field.path,
                        "Absolute or relative filesystem path to the .jfr file"),
                Prop.integer(Field.container_memory_mb,
                        "Container memory limit in MB (e.g. 2048 for 2Gi). "
                                + "If omitted, container_fit is not evaluable.").optional(),
                Prop.integer(Field.default_stack_size_kb,
                        "Fallback thread stack size in KB when -Xss is not in JVM args (default 1024).")
                        .optional()
        );
    }

    @Override
    public String execute(JsonObject input) {
        if (!input.has(Field.path.name())) {
            return "Error: Missing required parameter: path";
        }
        var path = Path.of(input.getString(Field.path.name()));
        if (!Files.exists(path)) return "Error: JFR file not found: " + path;
        if (!Files.isRegularFile(path)) return "Error: Not a regular file: " + path;

        Integer containerMb = null;
        if (input.has(Field.container_memory_mb.name())
                && !JsonObject.NULL.equals(input.get(Field.container_memory_mb.name()))) {
            try {
                int v = input.getInt(Field.container_memory_mb.name());
                if (v > 0) containerMb = v;
            } catch (RuntimeException ignored) {}
        }

        int defaultStackKb = DEFAULT_STACK_KB;
        if (input.has(Field.default_stack_size_kb.name())) {
            try {
                int v = input.getInt(Field.default_stack_size_kb.name());
                if (v > 0) defaultStackKb = v;
            } catch (RuntimeException ignored) {}
        }

        try {
            return analyze(path, containerMb, defaultStackKb).toString(2);
        } catch (IOException e) {
            return "Error: Could not read JFR file (" + e.getClass().getSimpleName() + ")";
        }
    }

    static JsonObject analyze(Path path, Integer containerMb, int defaultStackKb) throws IOException {
        var nmtByCategory = new LinkedHashMap<String, long[]>();
        long[] nmtTotalEvent = null;

        Long msCommitted = null, msUsed = null, msReserved = null;
        Long csCommitted = null, csUsed = null;

        var codeCacheByBlob = new LinkedHashMap<String, long[]>();
        Long codeCacheReserved = null;

        long maxActive = 0;
        long sumActive = 0;
        long countThreadStats = 0;
        long maxDaemon = 0;
        long peakCount = 0;

        Long heapCommitted = null;
        long maxHeapUsed = -1;
        Long heapReserved = null;

        String jvmArgs = null;

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
                    case "jdk.NativeMemoryUsage" -> {
                        String cat = tryGetString(e, "type");
                        Long r = tryGetLong(e, "reserved");
                        Long c = tryGetLong(e, "committed");
                        if (cat != null && r != null && c != null) {
                            nmtByCategory.put(cat, new long[]{r, c});
                        }
                    }
                    case "jdk.NativeMemoryUsageTotal" -> {
                        Long r = tryGetLong(e, "reserved");
                        Long c = tryGetLong(e, "committed");
                        if (r != null && c != null) {
                            nmtTotalEvent = new long[]{r, c};
                        }
                    }
                    case "jdk.MetaspaceSummary" -> {
                        long[] ms = readSizes(e, "metaspace");
                        if (ms != null) {
                            msCommitted = ms[0];
                            msUsed = ms[1];
                            msReserved = ms[2];
                        }
                        long[] cs = readSizes(e, "classSpace");
                        if (cs != null) {
                            csCommitted = cs[0];
                            csUsed = cs[1];
                        }
                    }
                    case "jdk.CodeCacheStatistics" -> {
                        String blob = tryGetString(e, "codeBlobType");
                        Long start = tryGetLong(e, "startAddress");
                        Long top = tryGetLong(e, "reservedTopAddress");
                        Long unalloc = tryGetLong(e, "unallocatedCapacity");
                        Integer fullCnt = tryGetInt(e, "fullCount");
                        if (blob != null && start != null && top != null && top >= start) {
                            long committed = top - start;
                            long used = unalloc != null ? Math.max(committed - unalloc, 0L) : committed;
                            codeCacheByBlob.put(blob, new long[]{committed, used,
                                    fullCnt == null ? 0 : fullCnt});
                        }
                    }
                    case "jdk.CodeCacheConfiguration" -> {
                        Long rsv = tryGetLong(e, "reservedSize");
                        if (rsv != null && rsv > 0) codeCacheReserved = rsv;
                    }
                    case "jdk.JavaThreadStatistics" -> {
                        Long active = tryGetLong(e, "activeCount");
                        Long daemon = tryGetLong(e, "daemonCount");
                        Long peak = tryGetLong(e, "peakCount");
                        if (active != null) {
                            if (active > maxActive) maxActive = active;
                            sumActive += active;
                            countThreadStats++;
                        }
                        if (daemon != null && daemon > maxDaemon) maxDaemon = daemon;
                        if (peak != null && peak > peakCount) peakCount = peak;
                    }
                    case "jdk.GCHeapSummary" -> {
                        Long hu = tryGetLong(e, "heapUsed");
                        if (hu != null && hu > maxHeapUsed) maxHeapUsed = hu;
                        if (e.hasField("heapSpace")) {
                            try {
                                Object v = e.getValue("heapSpace");
                                if (v instanceof RecordedObject ro) {
                                    Long committed = tryGetLong(ro, "committedSize");
                                    Long reserved = tryGetLong(ro, "reservedSize");
                                    if (committed != null) heapCommitted = committed;
                                    if (reserved != null) heapReserved = reserved;
                                }
                            } catch (RuntimeException ignored) {}
                        }
                    }
                    case "jdk.JVMInformation" -> {
                        if (jvmArgs == null) jvmArgs = tryGetString(e, "jvmArguments");
                    }
                    default -> {}
                }
            }
        }

        double durationSeconds = 0.0;
        if (earliest != null && latest != null) {
            durationSeconds = Duration.between(earliest, latest).toMillis() / 1000.0;
        }

        boolean nmtAvailable = !nmtByCategory.isEmpty();
        long stackKb;
        String stackSource;
        Integer xss = parseXssKb(jvmArgs);
        if (xss != null && xss > 0) {
            stackKb = xss;
            stackSource = "jvm_args";
        } else {
            stackKb = defaultStackKb;
            stackSource = "default";
        }

        var result = new JsonObject();

        result.put("recording", new JsonObject()
                .put("path", path.toString())
                .put("duration_seconds", round1(durationSeconds)));

        var ctx = new JsonObject();
        ctx.put("limit_provided", containerMb != null);
        ctx.put("memory_limit_mb", containerMb == null ? JsonObject.NULL : containerMb);
        result.put("container_context", ctx);

        var nmt = new JsonObject();
        nmt.put("available", nmtAvailable);
        nmt.put("categories_count", nmtByCategory.size());
        nmt.put("fallback_reason", nmtAvailable ? JsonObject.NULL
                : "Native Memory Tracking not enabled in the source recording. Restart the JVM with "
                + "-XX:NativeMemoryTracking=summary to capture per-category memory data.");
        result.put("nmt", nmt);

        var heap = new JsonObject();
        heap.put("committed_mb", heapCommitted == null ? JsonObject.NULL : round1(toMb(heapCommitted)));
        heap.put("max_used_mb", maxHeapUsed < 0 ? JsonObject.NULL : round1(toMb(maxHeapUsed)));
        heap.put("reserved_mb", heapReserved == null ? JsonObject.NULL : round1(toMb(heapReserved)));
        result.put("heap", heap);

        var ms = new JsonObject();
        boolean msAvail = msCommitted != null || msUsed != null || msReserved != null;
        ms.put("available", msAvail);
        ms.put("committed_mb", msCommitted == null ? JsonObject.NULL : round1(toMb(msCommitted)));
        ms.put("used_mb", msUsed == null ? JsonObject.NULL : round1(toMb(msUsed)));
        ms.put("reserved_mb", msReserved == null ? JsonObject.NULL : round1(toMb(msReserved)));
        ms.put("class_space_committed_mb",
                csCommitted == null ? JsonObject.NULL : round1(toMb(csCommitted)));
        ms.put("class_space_used_mb",
                csUsed == null ? JsonObject.NULL : round1(toMb(csUsed)));
        result.put("metaspace", ms);

        long ccCommittedTotal = 0;
        long ccUsedTotal = 0;
        long ccFullWarnings = 0;
        for (var v : codeCacheByBlob.values()) {
            ccCommittedTotal += v[0];
            ccUsedTotal += v[1];
            ccFullWarnings += v[2];
        }
        var cc = new JsonObject();
        boolean ccAvail = !codeCacheByBlob.isEmpty() || codeCacheReserved != null;
        cc.put("available", ccAvail);
        cc.put("reserved_mb", codeCacheReserved == null ? JsonObject.NULL : round1(toMb(codeCacheReserved)));
        cc.put("committed_mb", codeCacheByBlob.isEmpty() ? JsonObject.NULL : round1(toMb(ccCommittedTotal)));
        cc.put("used_estimate_mb", codeCacheByBlob.isEmpty() ? JsonObject.NULL : round1(toMb(ccUsedTotal)));
        cc.put("full_warnings", ccFullWarnings);
        result.put("code_cache", cc);

        var threads = new JsonObject();
        threads.put("active_count_max", maxActive);
        threads.put("active_count_avg",
                countThreadStats == 0 ? 0L : Math.round((double) sumActive / countThreadStats));
        threads.put("daemon_count_max", maxDaemon);
        threads.put("peak_count", peakCount);
        threads.put("stack_size_kb_used", stackKb);
        threads.put("stack_size_source", stackSource);
        threads.put("estimated_stack_total_mb", round1(maxActive * stackKb / 1024.0));
        result.put("threads", threads);

        long nmtTotalReserved = 0;
        long nmtTotalCommitted = 0;
        if (nmtTotalEvent != null) {
            nmtTotalReserved = nmtTotalEvent[0];
            nmtTotalCommitted = nmtTotalEvent[1];
        } else {
            for (var v : nmtByCategory.values()) {
                nmtTotalReserved += v[0];
                nmtTotalCommitted += v[1];
            }
        }

        var sortedNmt = new ArrayList<>(nmtByCategory.entrySet());
        sortedNmt.sort(Comparator.<Map.Entry<String, long[]>>comparingLong(en -> en.getValue()[1]).reversed());
        var nmtArr = new JsonArray();
        String dominantCategory = null;
        long dominantCommitted = -1;
        for (var en : sortedNmt) {
            long r = en.getValue()[0];
            long c = en.getValue()[1];
            double pct = nmtTotalCommitted == 0 ? 0.0 : 100.0 * c / nmtTotalCommitted;
            nmtArr.put(new JsonObject()
                    .put("category", en.getKey())
                    .put("reserved_mb", round1(toMb(r)))
                    .put("committed_mb", round1(toMb(c)))
                    .put("pct_of_committed_total", round1(pct)));
            if (c > dominantCommitted) {
                dominantCommitted = c;
                dominantCategory = en.getKey();
            }
        }
        result.put("native_memory_by_category", nmtArr);

        var nmtTotal = new JsonObject();
        if (nmtAvailable) {
            nmtTotal.put("reserved_mb", round1(toMb(nmtTotalReserved)));
            nmtTotal.put("committed_mb", round1(toMb(nmtTotalCommitted)));
        } else {
            nmtTotal.put("reserved_mb", JsonObject.NULL);
            nmtTotal.put("committed_mb", JsonObject.NULL);
        }
        result.put("native_memory_total", nmtTotal);

        var fit = new JsonObject();
        if (!nmtAvailable) {
            fit.put("evaluable", false);
            fit.put("evaluable_reason",
                    "NMT not available — cannot compute total JVM committed memory");
            fit.put("total_committed_mb", JsonObject.NULL);
            fit.put("container_limit_mb", containerMb == null ? JsonObject.NULL : containerMb);
            fit.put("headroom_mb", JsonObject.NULL);
            fit.put("headroom_pct", JsonObject.NULL);
            fit.put("verdict", JsonObject.NULL);
            fit.put("dominant_category", JsonObject.NULL);
        } else if (containerMb == null) {
            fit.put("evaluable", false);
            fit.put("evaluable_reason", "No container_memory_mb provided");
            fit.put("total_committed_mb", round1(toMb(nmtTotalCommitted)));
            fit.put("container_limit_mb", JsonObject.NULL);
            fit.put("headroom_mb", JsonObject.NULL);
            fit.put("headroom_pct", JsonObject.NULL);
            fit.put("verdict", JsonObject.NULL);
            fit.put("dominant_category", dominantCategory == null ? JsonObject.NULL : dominantCategory);
        } else {
            double totalMb = toMb(nmtTotalCommitted);
            double headroomMb = containerMb - totalMb;
            double headroomPct = containerMb == 0 ? 0.0 : 100.0 * headroomMb / containerMb;
            // "exceeded" must mean what it says: total committed memory is
            // actually over the container limit. A recording that fits with
            // very little headroom is "at_risk", not "exceeded" — the old
            // `headroomPct < 5.0` clause here fired "exceeded" on committed
            // memory that was still under the limit, producing a report line
            // like "EXCEEDED — 981 MB of 1000 MB (1.9% headroom)" that
            // contradicts its own numbers.
            String verdict;
            if (totalMb > containerMb) verdict = "exceeded";
            else if (headroomPct < 15.0) verdict = "at_risk";
            else if (headroomPct <= 40.0) verdict = "tight";
            else verdict = "safe";
            fit.put("evaluable", true);
            fit.put("evaluable_reason", JsonObject.NULL);
            fit.put("total_committed_mb", round1(totalMb));
            fit.put("container_limit_mb", containerMb);
            fit.put("headroom_mb", round1(headroomMb));
            fit.put("headroom_pct", round1(headroomPct));
            fit.put("verdict", verdict);
            fit.put("dominant_category", dominantCategory == null ? JsonObject.NULL : dominantCategory);
        }
        result.put("container_fit", fit);

        boolean maxMetaInArgs = jvmArgs != null && jvmArgs.contains("-XX:MaxMetaspaceSize");
        boolean metaspaceUnbounded =
                msReserved != null && msReserved >= ONE_GB_BYTES && !maxMetaInArgs;
        boolean metaspaceNearCommitted =
                msCommitted != null && msCommitted > 0 && msUsed != null
                        && (double) msUsed / msCommitted > 0.9;
        boolean codeCacheNearFull = false;
        if (codeCacheReserved != null && codeCacheReserved > 0 && ccUsedTotal > 0) {
            codeCacheNearFull = (double) ccUsedTotal / codeCacheReserved > 0.85;
        }
        if (ccFullWarnings > 0) codeCacheNearFull = true;

        var signals = new JsonObject();
        signals.put("enable_nmt_recommended", !nmtAvailable);
        signals.put("metaspace_unbounded", metaspaceUnbounded);
        signals.put("metaspace_near_committed", metaspaceNearCommitted);
        signals.put("code_cache_near_full", codeCacheNearFull);
        signals.put("thread_count_high", maxActive > 200);
        signals.put("container_limit_missing", containerMb == null);
        result.put("signals", signals);

        return result;
    }

    static long[] readSizes(RecordedEvent e, String field) {
        try {
            if (!e.hasField(field)) return null;
            Object v = e.getValue(field);
            if (v instanceof RecordedObject ro) {
                Long c = tryGetLong(ro, "committed");
                Long u = tryGetLong(ro, "used");
                Long r = tryGetLong(ro, "reserved");
                if (c == null && u == null && r == null) return null;
                return new long[]{
                        c == null ? 0 : c,
                        u == null ? 0 : u,
                        r == null ? 0 : r,
                };
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    static Integer parseXssKb(String args) {
        if (args == null) return null;
        int idx = args.indexOf("-Xss");
        if (idx < 0) return null;
        int start = idx + 4;
        int end = start;
        while (end < args.length() && !Character.isWhitespace(args.charAt(end))) end++;
        String val = args.substring(start, end);
        if (val.isEmpty()) return null;
        char suffix = val.charAt(val.length() - 1);
        long mul;
        String num;
        if (suffix == 'k' || suffix == 'K') { mul = 1L;            num = val.substring(0, val.length() - 1); }
        else if (suffix == 'm' || suffix == 'M') { mul = 1024L;     num = val.substring(0, val.length() - 1); }
        else if (suffix == 'g' || suffix == 'G') { mul = 1024L * 1024; num = val.substring(0, val.length() - 1); }
        else { mul = 0L; num = val; }
        try {
            long n = Long.parseLong(num);
            long kb = mul == 0 ? n / 1024L : n * mul;
            if (kb <= 0) return null;
            return (int) Math.min(kb, Integer.MAX_VALUE);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static Integer parseMemoryToMb(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        long mul;
        String num;
        if (v.endsWith("Gi") || v.endsWith("gi")) { mul = 1024L;        num = v.substring(0, v.length() - 2); }
        else if (v.endsWith("Mi") || v.endsWith("mi")) { mul = 1L;       num = v.substring(0, v.length() - 2); }
        else if (v.endsWith("Ki") || v.endsWith("ki")) { mul = -1024L;   num = v.substring(0, v.length() - 2); }
        else if (v.endsWith("G") || v.endsWith("g"))  { mul = 1024L;     num = v.substring(0, v.length() - 1); }
        else if (v.endsWith("M") || v.endsWith("m"))  { mul = 1L;        num = v.substring(0, v.length() - 1); }
        else if (v.endsWith("K") || v.endsWith("k"))  { mul = -1024L;    num = v.substring(0, v.length() - 1); }
        else { mul = 0L; num = v; }
        try {
            long n = Long.parseLong(num.trim());
            long mb = mul == 0 ? n / (1024L * 1024L) : (mul < 0 ? n / (-mul) : n * mul);
            if (mb <= 0) return null;
            return (int) Math.min(mb, Integer.MAX_VALUE);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String tryGetString(RecordedEvent e, String field) {
        try { if (e.hasField(field)) return e.getString(field); } catch (RuntimeException ignored) {}
        return null;
    }

    static Long tryGetLong(RecordedEvent e, String field) {
        try { if (e.hasField(field)) return e.getLong(field); } catch (RuntimeException ignored) {}
        return null;
    }

    static Long tryGetLong(RecordedObject o, String field) {
        try { if (o.hasField(field)) return o.getLong(field); } catch (RuntimeException ignored) {}
        return null;
    }

    static Integer tryGetInt(RecordedEvent e, String field) {
        try { if (e.hasField(field)) return e.getInt(field); } catch (RuntimeException ignored) {}
        return null;
    }

    static double toMb(double bytes) { return bytes / (1024.0 * 1024.0); }
    static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}
