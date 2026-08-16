package jfrdoc.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import jfrdoc.json.JsonArray;
import jfrdoc.json.JsonObject;

public class JfrExceptionsTool implements Tool {

    static final int DEFAULT_TOP_N = 15;
    static final int MIN_TOP_N = 1;
    static final int MAX_TOP_N = 100;
    static final String DEFAULT_FRAMEWORK = "other";
    static final int SAMPLE_MESSAGE_MAX = 120;

    static final List<String> ALL_CATEGORIES = List.of("user_code", "framework", "jdk", "native");

    static final Set<String> CONTROL_FLOW_CLASSES = Set.of(
            "java.lang.NumberFormatException",
            "java.util.NoSuchElementException",
            "java.lang.IllegalArgumentException",
            "java.lang.ClassNotFoundException",
            "java.lang.NoSuchMethodException",
            "java.lang.NoSuchFieldException"
    );

    @Override
    public String toolName() {
        return "jfr_exceptions";
    }

    @Override
    public String description() {
        return "Aggregates jdk.JavaExceptionThrow and jdk.JavaErrorThrow events to identify which exception classes "
                + "are being thrown, how frequently, and from where. Critical for detecting exception-driven control "
                + "flow (anti-pattern), repeated I/O failures (client disconnects, protocol errors), and runtime errors. "
                + "IMPORTANT: JFR's stock 'default' and 'profile' settings profiles disable jdk.JavaExceptionThrow — "
                + "check the event_availability block before concluding zero throws means no exceptions occurred; "
                + "it may mean the event type was off. jdk.JavaErrorThrow is typically on regardless. "
                + "Call this when summary.notable_events_present.exceptions is true.";
    }

    enum Field { path, top_n, framework }

    @Override
    public String inputSchema() {
        return Tool.schema(
                Prop.string(Field.path,
                        "Absolute or relative filesystem path to the .jfr file"),
                Prop.integer(Field.top_n,
                        "Number of top exception classes / throwing sites to return (clamped to [1,100]; default 15)").optional(),
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
            return "Error: Could not load categorization rules (" + e.getClass().getSimpleName() + ")";
        }

        try {
            return analyze(path, topN, framework, categorizer).toString(2);
        } catch (IOException e) {
            return "Error: Could not read JFR file (" + e.getClass().getSimpleName() + ")";
        }
    }

    static JsonObject analyze(Path path, int topN, String framework, FrameworkCategorizer categorizer) throws IOException {
        var classAgg = new LinkedHashMap<String, ExceptionClassStats>();
        var siteAgg = new LinkedHashMap<String, ThrowSiteStats>();

        long totalExceptions = 0;
        long totalErrors = 0;
        long withStackTrace = 0;
        long withoutStackTrace = 0;

        Instant earliest = null;
        Instant latest = null;

        // JFR's stock "default" and "profile" settings profiles disable
        // jdk.JavaExceptionThrow (jdk.JavaErrorThrow stays on). Zero events
        // then means "the event type was off", not "no exceptions occurred" —
        // those are very different findings. Resolve each type's numeric id
        // so the jdk.ActiveSetting stream (scanned in the same pass below)
        // can report which is actually true.
        Long exceptionTypeId = null;
        Long errorTypeId = null;
        try (var rf = new RecordingFile(path)) {
            for (var eventType : rf.readEventTypes()) {
                if ("jdk.JavaExceptionThrow".equals(eventType.getName())) exceptionTypeId = eventType.getId();
                else if ("jdk.JavaErrorThrow".equals(eventType.getName())) errorTypeId = eventType.getId();
            }
        }
        Boolean exceptionThrowEnabled = null;
        Boolean errorThrowEnabled = null;

        try (var rf = new RecordingFile(path)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String type = e.getEventType().getName();

                // Recording span must come from every event, not just
                // exception/error events — otherwise duration_seconds (and
                // every throws_per_second derived from it) reflects only the
                // window exceptions happened to occur in, not the actual
                // recording length.
                var ts = e.getStartTime();
                if (earliest == null || ts.isBefore(earliest)) earliest = ts;
                if (latest == null || ts.isAfter(latest)) latest = ts;

                if ("jdk.ActiveSetting".equals(type)
                        && e.hasField("id") && e.hasField("name") && e.hasField("value")
                        && "enabled".equals(e.getString("name"))) {
                    long settingId = e.getLong("id");
                    boolean value = Boolean.parseBoolean(e.getString("value"));
                    if (exceptionTypeId != null && settingId == exceptionTypeId) exceptionThrowEnabled = value;
                    if (errorTypeId != null && settingId == errorTypeId) errorThrowEnabled = value;
                }

                boolean isException = "jdk.JavaExceptionThrow".equals(type);
                boolean isError = "jdk.JavaErrorThrow".equals(type);
                if (!isException && !isError) continue;

                if (isException) totalExceptions++;
                else totalErrors++;

                String className = exceptionClassName(e);
                String message = tryGetString(e, "message");

                var cstats = classAgg.computeIfAbsent(className, k -> new ExceptionClassStats());
                cstats.events++;
                if (cstats.sampleMessage == null && message != null && !message.isEmpty()) {
                    // The application's own exception message is freeform text and
                    // can carry emails, secrets, or credential-bearing URLs (e.g. a
                    // connection-refused message embedding a JDBC URL) — redact
                    // before it ever reaches the model's context.
                    cstats.sampleMessage = Redaction.redactSecretsAndPii(message);
                }

                RecordedStackTrace trace = e.getStackTrace();
                List<RecordedFrame> frames = trace == null ? null : trace.getFrames();
                if (trace == null || frames == null || frames.isEmpty()) {
                    withoutStackTrace++;
                    continue;
                }
                withStackTrace++;

                int siteIdx = findThrowSiteFrameIndex(frames, className);
                if (siteIdx < 0) continue;

                RecordedFrame siteFrame = frames.get(siteIdx);
                String siteKey;
                String category;
                if (!siteFrame.isJavaFrame()) {
                    siteKey = nativeFrameKey(siteFrame);
                    category = "native";
                } else {
                    RecordedMethod method = siteFrame.getMethod();
                    RecordedClass cls = method == null ? null : method.getType();
                    if (method == null || cls == null || cls.getName() == null) {
                        continue;
                    }
                    String fqcn = cls.getName();
                    int line = siteFrame.getLineNumber();
                    siteKey = fqcn + "." + method.getName() + (line >= 0 ? ":" + line : "");
                    category = categorizer.categorize(fqcn);
                }

                cstats.siteCounts.merge(siteKey, 1L, Long::sum);
                cstats.siteCategories.put(siteKey, category);

                var sstats = siteAgg.computeIfAbsent(siteKey, k -> new ThrowSiteStats(category));
                sstats.events++;
                sstats.exceptionClassCounts.merge(className, 1L, Long::sum);

                String caller = callerSignature(frames, siteIdx + 1);
                if (caller != null) {
                    sstats.callerCounts.merge(caller, 1L, Long::sum);
                }
            }
        }

        long totalThrows = totalExceptions + totalErrors;
        long unknownEvents = classAgg.containsKey("<unknown>") ? classAgg.get("<unknown>").events : 0L;
        long identifiedClassEvents = totalThrows - unknownEvents;

        double durationSeconds = 0.0;
        if (earliest != null && latest != null) {
            durationSeconds = Duration.between(earliest, latest).toMillis() / 1000.0;
        }
        double throwsPerSecond = durationSeconds <= 0 ? 0.0 : totalExceptions / durationSeconds;

        Map<String, long[]> categoryEvents = new LinkedHashMap<>();
        Map<String, Set<String>> categoryClasses = new LinkedHashMap<>();
        for (String c : ALL_CATEGORIES) {
            categoryEvents.put(c, new long[1]);
            categoryClasses.put(c, new HashSet<>());
        }
        for (var en : siteAgg.entrySet()) {
            var s = en.getValue();
            long[] arr = categoryEvents.get(s.category);
            if (arr != null) arr[0] += s.events;
            var classes = categoryClasses.get(s.category);
            if (classes != null) classes.addAll(s.exceptionClassCounts.keySet());
        }
        long categorizedTotal = 0;
        for (long[] v : categoryEvents.values()) categorizedTotal += v[0];

        var result = new JsonObject();

        var recording = new JsonObject()
                .put("path", path.toString())
                .put("duration_seconds", round1(durationSeconds));
        result.put("recording", recording);

        // Distinguishes "this event type was off" from "it was on and simply
        // saw nothing" — a zero count means very different things depending
        // on which. null means the recording's jdk.ActiveSetting stream
        // didn't say either way (unusual, but not impossible).
        var eventAvailability = new JsonObject()
                .put("java_exception_throw_enabled",
                        exceptionThrowEnabled == null ? JsonObject.NULL : exceptionThrowEnabled)
                .put("java_error_throw_enabled",
                        errorThrowEnabled == null ? JsonObject.NULL : errorThrowEnabled);
        result.put("event_availability", eventAvailability);

        int uniqueClasses = classAgg.size();
        if (classAgg.containsKey("<unknown>")) uniqueClasses--;

        var summary = new JsonObject()
                .put("total_exceptions_thrown", totalExceptions)
                .put("total_errors_thrown", totalErrors)
                .put("throws_per_second", round1(throwsPerSecond))
                .put("unique_exception_classes", uniqueClasses)
                .put("with_stack_trace", withStackTrace)
                .put("without_stack_trace", withoutStackTrace);
        result.put("summary", summary);

        var byCategory = new JsonObject();
        for (String c : ALL_CATEGORIES) {
            long events = categoryEvents.get(c)[0];
            double pct = categorizedTotal == 0 ? 0.0 : round1(100.0 * events / categorizedTotal);
            byCategory.put(c, new JsonObject()
                    .put("events", events)
                    .put("pct", pct)
                    .put("unique_classes", categoryClasses.get(c).size()));
        }
        result.put("by_category", byCategory);

        var classList = new ArrayList<>(classAgg.entrySet());
        classList.removeIf(en -> "<unknown>".equals(en.getKey()));
        classList.sort(Comparator.<Map.Entry<String, ExceptionClassStats>>comparingLong(en -> en.getValue().events).reversed());

        var topClasses = new JsonArray();
        int rank = 1;
        for (var en : classList) {
            if (rank > topN) break;
            var s = en.getValue();
            double pct = identifiedClassEvents == 0 ? 0.0 : round1(100.0 * s.events / identifiedClassEvents);
            double tps = durationSeconds <= 0 ? 0.0 : s.events / durationSeconds;
            var entry = new JsonObject();
            entry.put("rank", rank);
            entry.put("class", en.getKey());
            entry.put("events", s.events);
            entry.put("pct_of_total", pct);
            entry.put("throws_per_second", round1(tps));
            entry.put("sample_message",
                    s.sampleMessage == null ? JsonObject.NULL : truncate(s.sampleMessage, SAMPLE_MESSAGE_MAX));

            String topSite = null;
            long topSiteCount = -1;
            for (var se : s.siteCounts.entrySet()) {
                if (se.getValue() > topSiteCount) {
                    topSiteCount = se.getValue();
                    topSite = se.getKey();
                }
            }
            entry.put("top_throwing_site", topSite == null ? JsonObject.NULL : topSite);
            entry.put("top_site_category", topSite == null ? JsonObject.NULL : s.siteCategories.get(topSite));

            topClasses.put(entry);
            rank++;
        }
        result.put("top_exception_classes", topClasses);

        var siteList = new ArrayList<>(siteAgg.entrySet());
        siteList.sort(Comparator.<Map.Entry<String, ThrowSiteStats>>comparingLong(en -> en.getValue().events).reversed());

        var topSites = new JsonArray();
        rank = 1;
        for (var en : siteList) {
            if (rank > topN) break;
            var s = en.getValue();
            double pct = identifiedClassEvents == 0 ? 0.0 : round1(100.0 * s.events / identifiedClassEvents);

            String domClass = null;
            long domClassCount = -1;
            for (var ce : s.exceptionClassCounts.entrySet()) {
                if (ce.getValue() > domClassCount) {
                    domClassCount = ce.getValue();
                    domClass = ce.getKey();
                }
            }
            double domShare = s.events == 0 ? 0.0 : round1(100.0 * domClassCount / s.events);

            String topCaller = null;
            long topCallerCount = -1;
            for (var ce : s.callerCounts.entrySet()) {
                if (ce.getValue() > topCallerCount) {
                    topCallerCount = ce.getValue();
                    topCaller = ce.getKey();
                }
            }

            var entry = new JsonObject();
            entry.put("rank", rank);
            entry.put("site", en.getKey());
            entry.put("category", s.category);
            entry.put("events", s.events);
            entry.put("pct_of_total", pct);
            entry.put("dominant_exception_class", domClass == null ? JsonObject.NULL : domClass);
            entry.put("dominant_exception_share_pct", domShare);
            entry.put("top_caller", topCaller == null ? JsonObject.NULL : topCaller);
            topSites.put(entry);
            rank++;
        }
        result.put("top_throwing_sites", topSites);

        var signals = new JsonObject();
        boolean throwRateHigh = throwsPerSecond > 50;
        boolean throwRateVeryHigh = throwsPerSecond > 500;

        String dominantClass = null;
        double dominantClassPct = 0.0;
        if (!classList.isEmpty()) {
            var top = classList.get(0);
            dominantClass = top.getKey();
            dominantClassPct = identifiedClassEvents == 0
                    ? 0.0 : round1(100.0 * top.getValue().events / identifiedClassEvents);
        }
        boolean singleClassDominant = dominantClassPct > 60.0;

        boolean singleSiteDominant = false;
        if (!siteList.isEmpty()) {
            long topSiteEvents = siteList.get(0).getValue().events;
            double topSitePct = identifiedClassEvents == 0 ? 0.0 : 100.0 * topSiteEvents / identifiedClassEvents;
            singleSiteDominant = topSitePct > 60.0;
        }

        boolean controlFlow = false;
        if (throwRateHigh && dominantClass != null && !classList.isEmpty()) {
            if (CONTROL_FLOW_CLASSES.contains(dominantClass)) {
                controlFlow = true;
            } else {
                var topEntry = classList.get(0);
                String topSiteForClass = null;
                long topSiteCnt = -1;
                for (var se : topEntry.getValue().siteCounts.entrySet()) {
                    if (se.getValue() > topSiteCnt) {
                        topSiteCnt = se.getValue();
                        topSiteForClass = se.getKey();
                    }
                }
                if (topSiteForClass != null) {
                    String cat = topEntry.getValue().siteCategories.get(topSiteForClass);
                    if ("user_code".equals(cat)) controlFlow = true;
                }
            }
        }

        signals.put("throw_rate_high", throwRateHigh);
        signals.put("throw_rate_very_high", throwRateVeryHigh);
        signals.put("single_class_dominant", singleClassDominant);
        signals.put("single_site_dominant", singleSiteDominant);
        signals.put("dominant_class", dominantClass == null ? JsonObject.NULL : dominantClass);
        signals.put("dominant_class_pct", dominantClassPct);
        signals.put("control_flow_smell", controlFlow);
        signals.put("framework_used_for_categorization", framework);
        result.put("signals", signals);

        return result;
    }

    static String exceptionClassName(RecordedEvent e) {
        try {
            if (e.hasField("thrownClass")) {
                RecordedClass cls = e.getClass("thrownClass");
                if (cls != null) {
                    String n = cls.getName();
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (RuntimeException ignored) {}
        return "<unknown>";
    }

    static String tryGetString(RecordedEvent e, String field) {
        try {
            if (e.hasField(field)) return e.getString(field);
        } catch (RuntimeException ignored) {}
        return null;
    }

    static String nativeFrameKey(RecordedFrame frame) {
        RecordedMethod m = frame.getMethod();
        if (m == null) return "<native>";
        RecordedClass cls = m.getType();
        if (cls == null || cls.getName() == null) return "<native>";
        return cls.getName() + "." + m.getName() + " (native)";
    }

    static String callerSignature(List<RecordedFrame> frames, int startIdx) {
        if (startIdx < 0 || startIdx >= frames.size()) return null;
        RecordedFrame caller = frames.get(startIdx);
        if (!caller.isJavaFrame()) return null;
        RecordedMethod cm = caller.getMethod();
        if (cm == null) return null;
        RecordedClass ct = cm.getType();
        if (ct == null || ct.getName() == null) return null;
        int line = caller.getLineNumber();
        return ct.getName() + "." + cm.getName() + (line >= 0 ? ":" + line : "");
    }

    static int findThrowSiteFrameIndex(List<RecordedFrame> frames, String thrownClass) {
        for (int i = 0; i < frames.size(); i++) {
            if (!isExceptionConstructorFrame(frames.get(i), thrownClass)) return i;
        }
        return -1;
    }

    static boolean isExceptionConstructorFrame(RecordedFrame f, String thrownClass) {
        if (!f.isJavaFrame()) return false;
        RecordedMethod m = f.getMethod();
        if (m == null) return false;
        if (!"<init>".equals(m.getName())) return false;
        RecordedClass cls = m.getType();
        if (cls == null || cls.getName() == null) return false;
        String fqcn = cls.getName();
        return fqcn.equals(thrownClass)
                || fqcn.equals("java.lang.Throwable")
                || fqcn.endsWith("Exception")
                || fqcn.endsWith("Error");
    }

    static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static final class ExceptionClassStats {
        long events;
        String sampleMessage;
        final Map<String, Long> siteCounts = new HashMap<>();
        final Map<String, String> siteCategories = new HashMap<>();
    }

    static final class ThrowSiteStats {
        final String category;
        long events;
        final Map<String, Long> exceptionClassCounts = new HashMap<>();
        final Map<String, Long> callerCounts = new HashMap<>();

        ThrowSiteStats(String category) {
            this.category = category;
        }
    }
}
