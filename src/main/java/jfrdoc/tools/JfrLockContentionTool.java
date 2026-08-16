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

public class JfrLockContentionTool implements Tool {

    static final int DEFAULT_TOP_N = 10;
    static final int MIN_TOP_N = 1;
    static final int MAX_TOP_N = 50;

    static final String CAT_POOL_IDLE       = "pool_idle_wait";
    static final String CAT_CONN_POOL       = "connection_pool_wait";
    static final String CAT_LOCK_ACQUIRE    = "lock_acquire_wait";
    static final String CAT_FUTURE          = "future_wait";
    static final String CAT_CONDITION       = "condition_wait";
    static final String CAT_SCHEDULED       = "scheduled_task_wait";
    static final String CAT_OTHER           = "other";

    static final List<String> ALL_CATEGORIES = List.of(
            CAT_POOL_IDLE, CAT_CONN_POOL, CAT_LOCK_ACQUIRE, CAT_FUTURE,
            CAT_CONDITION, CAT_SCHEDULED, CAT_OTHER);

    static final int CATEGORIZE_FRAME_DEPTH = 12;

    static final List<String> POOL_IDLE_PATTERNS = List.of(
            "java.util.concurrent.LinkedBlockingQueue.take",
            "java.util.concurrent.LinkedBlockingQueue.poll",
            "java.util.concurrent.SynchronousQueue.poll",
            "java.util.concurrent.SynchronousQueue.take",
            "java.util.concurrent.LinkedTransferQueue.take",
            "java.util.concurrent.LinkedTransferQueue.poll",
            "java.util.concurrent.ForkJoinPool.scan",
            "java.util.concurrent.ForkJoinPool.awaitWork",
            "java.util.concurrent.ThreadPoolExecutor$Worker.runWorker",
            "java.util.concurrent.ThreadPoolExecutor.getTask"
    );

    static final List<String> SCHEDULED_PATTERNS = List.of(
            "java.util.concurrent.ScheduledThreadPoolExecutor",
            "DelayedWorkQueue"
    );

    static final List<String> CONN_POOL_PATTERNS = List.of(
            "com.zaxxer.hikari",
            "org.apache.tomcat.jdbc.pool",
            "com.mchange.v2.c3p0",
            "org.apache.commons.dbcp2"
    );

    static final List<String> LOCK_ACQUIRE_PATTERNS = List.of(
            "java.util.concurrent.locks.ReentrantLock",
            "java.util.concurrent.locks.ReentrantReadWriteLock",
            "java.util.concurrent.locks.StampedLock",
            "java.util.concurrent.Semaphore.acquire",
            "java.util.concurrent.Phaser",
            "java.util.concurrent.CountDownLatch.await"
    );

    static final List<String> FUTURE_PATTERNS = List.of(
            "java.util.concurrent.CompletableFuture.get",
            "java.util.concurrent.CompletableFuture$Signaller",
            "java.util.concurrent.FutureTask.get"
    );

    static final List<String> CONDITION_EXACT_METHODS = List.of(
            "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.await",
            "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.awaitNanos",
            "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.awaitUninterruptibly"
    );

    @Override
    public String toolName() {
        return "jfr_lock_contention";
    }

    @Override
    public String description() {
        return "Analyzes lock contention and thread parking from jdk.JavaMonitorEnter and jdk.ThreadPark events. "
                + "JavaMonitorEnter indicates real synchronized-block contention (only fired above threshold). "
                + "ThreadPark is more nuanced — most occurrences are normal pool idle waits, NOT contention. "
                + "Returns top contended monitors, top park sites with heuristic category hints "
                + "(pool_idle_wait vs connection_pool_wait vs lock_acquire_wait etc.), and aggregate stats. "
                + "Call this always — degrades gracefully when no events present.";
    }

    enum Field { path, top_n }

    @Override
    public String inputSchema() {
        return Tool.schema(
                Prop.string(Field.path,
                        "Absolute or relative filesystem path to the .jfr file"),
                Prop.integer(Field.top_n,
                        "Number of top monitors/park sites to return (clamped to [1,50]; default 10)").optional()
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
            return "Error: Could not read JFR file (" + e.getClass().getSimpleName() + ")";
        }
    }

    static JsonObject analyze(Path path, int topN) throws IOException {
        var monitors = new LinkedHashMap<String, MonitorStats>();
        var monitorWait = new LinkedHashMap<String, SiteStats>();
        var parkSites = new LinkedHashMap<String, ParkStats>();
        var parkByCategory = new LinkedHashMap<String, long[]>();
        for (String c : ALL_CATEGORIES) parkByCategory.put(c, new long[2]);

        long monitorEnterCount = 0;
        long monitorEnterNanos = 0;
        long monitorEnterMaxNanos = 0;

        long monitorWaitCount = 0;
        long monitorWaitNanos = 0;

        long parkCount = 0;
        long parkNanos = 0;

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
                    case "jdk.JavaMonitorEnter" -> {
                        Duration d = tryGetDuration(e, "duration");
                        long ns = d == null ? 0L : d.toNanos();
                        monitorEnterCount++;
                        monitorEnterNanos += ns;
                        if (ns > monitorEnterMaxNanos) monitorEnterMaxNanos = ns;

                        String monClass = monitorClassName(e);
                        var stats = monitors.computeIfAbsent(monClass, k -> new MonitorStats());
                        stats.events++;
                        stats.totalNanos += ns;
                        if (ns > stats.maxNanos) stats.maxNanos = ns;

                        String callSite = topFrameSignature(e.getStackTrace());
                        if (callSite != null) {
                            stats.callSiteCounts.merge(callSite, 1L, Long::sum);
                        }
                    }
                    case "jdk.JavaMonitorWait" -> {
                        Duration d = tryGetDuration(e, "duration");
                        long ns = d == null ? 0L : d.toNanos();
                        monitorWaitCount++;
                        monitorWaitNanos += ns;

                        String site = topFrameSignature(e.getStackTrace());
                        if (site == null) continue;
                        var stats = monitorWait.computeIfAbsent(site, k -> new SiteStats());
                        stats.events++;
                        stats.totalNanos += ns;
                    }
                    case "jdk.ThreadPark" -> {
                        RecordedStackTrace trace = e.getStackTrace();
                        if (trace == null) continue;
                        List<RecordedFrame> frames = trace.getFrames();
                        if (frames == null || frames.isEmpty()) continue;

                        Duration d = tryGetDuration(e, "duration");
                        long ns = d == null ? 0L : d.toNanos();
                        parkCount++;
                        parkNanos += ns;

                        String site = parkSiteSignature(frames);
                        if (site == null) continue;
                        String category = categorizePark(frames);
                        long[] cat = parkByCategory.get(category);
                        cat[0]++;
                        cat[1] += ns;

                        var stats = parkSites.computeIfAbsent(site, k -> new ParkStats(category));
                        stats.events++;
                        stats.totalNanos += ns;
                        if (ns > stats.maxNanos) stats.maxNanos = ns;

                        String caller = parkCallerSignature(frames);
                        if (caller != null) {
                            stats.callerCounts.merge(caller, 1L, Long::sum);
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

        result.put("monitor_contention", buildMonitorContention(monitors, monitorEnterCount,
                monitorEnterNanos, monitorEnterMaxNanos, topN));
        result.put("monitor_wait", buildMonitorWait(monitorWait, monitorWaitCount, monitorWaitNanos, topN));
        result.put("thread_parking", buildThreadParking(parkSites, parkByCategory, parkCount, parkNanos, topN));
        result.put("signals", buildSignals(monitorEnterCount, parkByCategory));

        return result;
    }

    static JsonObject buildMonitorContention(Map<String, MonitorStats> monitors,
                                             long totalEvents, long totalNanos, long maxNanos, int topN) {
        var mc = new JsonObject();
        mc.put("total_events", totalEvents);
        mc.put("total_wait_time_ms", round1(toMs(totalNanos)));
        mc.put("max_wait_ms", round1(toMs(maxNanos)));

        var list = new ArrayList<>(monitors.entrySet());
        list.sort(Comparator.<Map.Entry<String, MonitorStats>>comparingLong(en -> en.getValue().totalNanos).reversed());

        var arr = new JsonArray();
        int rank = 1;
        for (var en : list) {
            if (rank > topN) break;
            var s = en.getValue();
            var entry = new JsonObject();
            entry.put("rank", rank);
            entry.put("monitor_class", en.getKey());
            entry.put("events", s.events);
            entry.put("total_wait_ms", round1(toMs(s.totalNanos)));
            entry.put("max_wait_ms", round1(toMs(s.maxNanos)));
            entry.put("avg_wait_ms", s.events == 0 ? 0.0 : round1(toMs(s.totalNanos) / s.events));
            entry.put("top_call_site", dominantKey(s.callSiteCounts));
            arr.put(entry);
            rank++;
        }
        mc.put("top_contended_monitors", arr);
        return mc;
    }

    static JsonObject buildMonitorWait(Map<String, SiteStats> wait, long totalEvents, long totalNanos, int topN) {
        var mw = new JsonObject();
        mw.put("total_events", totalEvents);
        mw.put("total_wait_time_ms", round1(toMs(totalNanos)));

        var list = new ArrayList<>(wait.entrySet());
        list.sort(Comparator.<Map.Entry<String, SiteStats>>comparingLong(en -> en.getValue().totalNanos).reversed());

        var arr = new JsonArray();
        int rank = 1;
        for (var en : list) {
            if (rank > topN) break;
            var s = en.getValue();
            var entry = new JsonObject();
            entry.put("rank", rank);
            entry.put("site", en.getKey());
            entry.put("events", s.events);
            entry.put("total_wait_ms", round1(toMs(s.totalNanos)));
            entry.put("avg_wait_ms", s.events == 0 ? 0.0 : round1(toMs(s.totalNanos) / s.events));
            arr.put(entry);
            rank++;
        }
        mw.put("top_wait_sites", arr);
        return mw;
    }

    static JsonObject buildThreadParking(Map<String, ParkStats> parkSites,
                                         Map<String, long[]> byCategory,
                                         long totalEvents, long totalNanos, int topN) {
        var tp = new JsonObject();
        tp.put("total_events", totalEvents);
        tp.put("total_park_time_ms", round1(toMs(totalNanos)));

        var catEntries = new ArrayList<Map.Entry<String, long[]>>(byCategory.entrySet());
        catEntries.sort(Comparator.<Map.Entry<String, long[]>>comparingLong(en -> en.getValue()[1]).reversed());
        var catArr = new JsonArray();
        for (var en : catEntries) {
            long[] v = en.getValue();
            double pct = totalNanos == 0 ? 0.0 : 100.0 * v[1] / totalNanos;
            catArr.put(new JsonObject()
                    .put("category", en.getKey())
                    .put("events", v[0])
                    .put("total_park_ms", round1(toMs(v[1])))
                    .put("pct_of_park_time", round1(pct)));
        }
        tp.put("by_category", catArr);

        var list = new ArrayList<>(parkSites.entrySet());
        list.sort(Comparator.<Map.Entry<String, ParkStats>>comparingLong(en -> en.getValue().totalNanos).reversed());
        var arr = new JsonArray();
        int rank = 1;
        for (var en : list) {
            if (rank > topN) break;
            var s = en.getValue();
            var entry = new JsonObject();
            entry.put("rank", rank);
            entry.put("site", en.getKey());
            entry.put("category_hint", s.category);
            entry.put("events", s.events);
            entry.put("total_park_ms", round1(toMs(s.totalNanos)));
            entry.put("avg_park_ms", s.events == 0 ? 0.0 : round1(toMs(s.totalNanos) / s.events));
            entry.put("max_park_ms", round1(toMs(s.maxNanos)));
            entry.put("top_caller", dominantKey(s.callerCounts));
            arr.put(entry);
            rank++;
        }
        tp.put("top_park_sites", arr);
        return tp;
    }

    static JsonObject buildSignals(long monitorEnterCount, Map<String, long[]> byCategory) {
        long lockAcquireEvents = byCategory.getOrDefault(CAT_LOCK_ACQUIRE, new long[2])[0];
        long lockAcquireNanos = byCategory.getOrDefault(CAT_LOCK_ACQUIRE, new long[2])[1];
        long connPoolEvents = byCategory.getOrDefault(CAT_CONN_POOL, new long[2])[0];
        long connPoolNanos = byCategory.getOrDefault(CAT_CONN_POOL, new long[2])[1];
        long poolIdleNanos = byCategory.getOrDefault(CAT_POOL_IDLE, new long[2])[1];
        long scheduledNanos = byCategory.getOrDefault(CAT_SCHEDULED, new long[2])[1];

        long totalParkNanos = 0;
        for (long[] v : byCategory.values()) totalParkNanos += v[1];

        double lockAcquirePct = totalParkNanos == 0 ? 0.0 : 100.0 * lockAcquireNanos / totalParkNanos;
        double connPoolPct = totalParkNanos == 0 ? 0.0 : 100.0 * connPoolNanos / totalParkNanos;
        double benignPct = totalParkNanos == 0 ? 0.0 : 100.0 * (poolIdleNanos + scheduledNanos) / totalParkNanos;

        boolean hasRealContention = monitorEnterCount > 0 || lockAcquireEvents > 100;
        boolean connPoolUnderPressure = connPoolPct > 5.0 && connPoolEvents > 50;
        boolean lockAcquireDominant = lockAcquirePct > 30.0;
        boolean parkBenign = benignPct > 80.0 && !hasRealContention;

        return new JsonObject()
                .put("has_real_contention", hasRealContention)
                .put("connection_pool_under_pressure", connPoolUnderPressure)
                .put("lock_acquire_dominant", lockAcquireDominant)
                .put("park_total_likely_benign", parkBenign);
    }

    static String categorizePark(List<RecordedFrame> frames) {
        int end = Math.min(frames.size(), CATEGORIZE_FRAME_DEPTH);
        var sigs = new ArrayList<String>(end);
        for (int i = 1; i < end; i++) {
            String sig = frameFqcnMethod(frames.get(i));
            if (sig != null) sigs.add(sig);
        }
        for (String sig : sigs) {
            for (String p : POOL_IDLE_PATTERNS) if (sig.contains(p)) return CAT_POOL_IDLE;
        }
        for (String sig : sigs) {
            for (String p : SCHEDULED_PATTERNS) if (sig.contains(p)) return CAT_SCHEDULED;
        }
        for (String sig : sigs) {
            for (String p : CONN_POOL_PATTERNS) if (sig.contains(p)) return CAT_CONN_POOL;
        }
        for (String sig : sigs) {
            for (String p : LOCK_ACQUIRE_PATTERNS) if (sig.contains(p)) return CAT_LOCK_ACQUIRE;
        }
        for (String sig : sigs) {
            for (String p : FUTURE_PATTERNS) if (sig.contains(p)) return CAT_FUTURE;
        }
        for (String sig : sigs) {
            for (String p : CONDITION_EXACT_METHODS) if (sig.equals(p)) return CAT_CONDITION;
        }
        return CAT_OTHER;
    }

    static String parkSiteSignature(List<RecordedFrame> frames) {
        for (int i = 1; i < frames.size(); i++) {
            String sig = frameWithLine(frames.get(i));
            if (sig != null) return sig;
        }
        return frameWithLine(frames.get(0));
    }

    static String parkCallerSignature(List<RecordedFrame> frames) {
        for (int i = 2; i < frames.size(); i++) {
            String sig = frameWithLine(frames.get(i));
            if (sig != null) return sig;
        }
        return null;
    }

    static String topFrameSignature(RecordedStackTrace trace) {
        if (trace == null) return null;
        List<RecordedFrame> frames = trace.getFrames();
        if (frames == null || frames.isEmpty()) return null;
        return frameWithLine(frames.get(0));
    }

    static String frameWithLine(RecordedFrame frame) {
        if (frame == null || !frame.isJavaFrame()) return null;
        RecordedMethod method = frame.getMethod();
        if (method == null) return null;
        RecordedClass cls = method.getType();
        if (cls == null || cls.getName() == null) return null;
        int line = frame.getLineNumber();
        return cls.getName() + "." + method.getName() + (line >= 0 ? ":" + line : "");
    }

    static String frameFqcnMethod(RecordedFrame frame) {
        if (frame == null || !frame.isJavaFrame()) return null;
        RecordedMethod method = frame.getMethod();
        if (method == null) return null;
        RecordedClass cls = method.getType();
        if (cls == null || cls.getName() == null) return null;
        return cls.getName() + "." + method.getName();
    }

    static String monitorClassName(RecordedEvent e) {
        try {
            if (e.hasField("monitorClass")) {
                RecordedClass cls = e.getClass("monitorClass");
                if (cls != null) {
                    String n = cls.getName();
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (RuntimeException ignored) {}
        return "<unknown>";
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

    static Duration tryGetDuration(RecordedEvent e, String field) {
        try {
            if (e.hasField(field)) return e.getDuration(field);
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

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static final class MonitorStats {
        long events;
        long totalNanos;
        long maxNanos;
        final Map<String, Long> callSiteCounts = new LinkedHashMap<>();
    }

    static final class SiteStats {
        long events;
        long totalNanos;
    }

    static final class ParkStats {
        final String category;
        long events;
        long totalNanos;
        long maxNanos;
        final Map<String, Long> callerCounts = new LinkedHashMap<>();

        ParkStats(String category) {
            this.category = category;
        }
    }

}
