package jfrdoc.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import jfrdoc.json.JsonArray;

/**
 * Reduces a recording's JVM startup and program arguments to what a
 * performance report needs, without passing on anything that could be a
 * credential, a path, or other runtime data.
 *
 * <p>Earlier versions sent both strings to the model verbatim, minus a
 * best-effort regex for {@code password=}-style secrets. A flag value can
 * carry any secret in any shape ({@code -Ddb.pwd=…}, {@code --token …},
 * {@code -XX:OnOutOfMemoryError="curl https://user:pass@…"}), so this is an
 * allowlist instead: what survives is chosen by shape, never by guessing
 * which values are secret.
 *
 * <ul>
 *   <li>{@code -XX:+Flag} / {@code -XX:-Flag}: kept.</li>
 *   <li>{@code -XX:Name=value}, {@code -Xmx512m}-style sizes: kept only when
 *       the value is a plain number, optionally with a size unit or %
 *       ({@code MaxRAMPercentage=75.0}, {@code -Xss512k}), or one of a
 *       flag's fixed keywords ({@link #KEYWORD_VALUES}). Any other value is
 *       replaced by {@code <omitted>}.</li>
 *   <li>{@code -Dname=value}: the property name only.</li>
 *   <li>{@code -javaagent:}, {@code -agentpath:}: the agent's file name only
 *       (it identifies the agent, e.g. an APM); options dropped.
 *       {@code -agentlib:}: the library name only.</li>
 *   <li>Other standard launcher/JVM options ({@link #KNOWN_OPTIONS}): the
 *       name only. Everything else is dropped — bare tokens such as a
 *       classpath, and unrecognized option-shaped tokens.</li>
 *   <li>Program arguments: only the main class or jar file name, plus a
 *       count of the rest.</li>
 * </ul>
 *
 * <p>JFR records each argument list as one space-joined string, so a value
 * that contained spaces ({@code -Dcmd=mysql -pS3cret}) comes back as several
 * tokens. That is why a generic "keep anything option-shaped" rule would
 * leak, and why unknown options are dropped rather than passed through.
 */
final class JvmArguments {

    private JvmArguments() {}

    static final String OMITTED = "<omitted>";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern PROPERTY_NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_.+-]+");
    private static final Pattern NUMERIC = Pattern.compile("[0-9]+(\\.[0-9]+)?[kKmMgGtT%]?");
    private static final Pattern SIZE_FLAG = Pattern.compile("-X(mx|ms|mn|ss)[0-9]+[kKmMgGtT]?");
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    /** -XX options whose value is a fixed keyword set rather than a number. */
    static final Map<String, Set<String>> KEYWORD_VALUES = Map.of(
            "NativeMemoryTracking", Set.of("off", "summary", "detail"));

    /** Launcher and -X options whose names are kept (values never are). */
    static final Set<String> KNOWN_OPTIONS = Set.of(
            "-server", "-client", "-ea", "-da", "-esa", "-dsa",
            "-enableassertions", "-disableassertions", "-enablesystemassertions", "-disablesystemassertions",
            "-verbose", "-cp", "-classpath", "--class-path", "-p", "--module-path", "--upgrade-module-path",
            "--add-modules", "--add-opens", "--add-exports", "--add-reads", "--limit-modules", "--patch-module",
            "--enable-preview", "--enable-native-access", "--illegal-access", "-jar", "-m", "--module",
            "-Xint", "-Xcomp", "-Xmixed", "-Xbatch", "-Xrs", "-Xshare", "-Xlog", "-Xnoclassgc", "-Xverify",
            "-Xdebug", "-Xcheck", "-Xdiag", "-Xfuture", "-Xincgc", "-Xprof", "-Xrunjdwp",
            "-Xbootclasspath", "-Xbootclasspath/a", "-Xbootclasspath/p", "-XshowSettings");

    /** Sanitized JVM options, in their original order. */
    static JsonArray jvmFlags(String jvmArguments) {
        var flags = new JsonArray();
        for (String token : tokens(jvmArguments)) {
            String kept = sanitize(token);
            if (kept != null) flags.put(kept);
        }
        return flags;
    }

    static String sanitize(String t) {
        if (t.startsWith("-XX:")) return xxFlag(t.substring(4));
        if (t.startsWith("-D")) {
            int eq = t.indexOf('=');
            String name = eq < 0 ? t.substring(2) : t.substring(2, eq);
            return PROPERTY_NAME.matcher(name).matches() ? "-D" + name : null;
        }
        if (SIZE_FLAG.matcher(t).matches()) return t;
        if (t.startsWith("-javaagent:")) return "-javaagent:" + agentFile(t.substring(11));
        if (t.startsWith("-agentpath:")) return "-agentpath:" + agentFile(t.substring(11));
        if (t.startsWith("-agentlib:")) {
            String lib = before(t.substring(10), '=');
            return "-agentlib:" + (FILE_NAME.matcher(lib).matches() ? lib : OMITTED);
        }
        int cut = firstOf(t, '=', ':');
        String name = cut < 0 ? t : t.substring(0, cut);
        if (!KNOWN_OPTIONS.contains(name)) return null;
        return cut < 0 ? name : name + t.charAt(cut) + OMITTED;
    }

    private static String xxFlag(String body) {
        if (body.startsWith("+") || body.startsWith("-")) {
            return NAME.matcher(body.substring(1)).matches() ? "-XX:" + body : null;
        }
        int eq = body.indexOf('=');
        String name = eq < 0 ? body : body.substring(0, eq);
        if (!NAME.matcher(name).matches()) return null;
        if (eq < 0) return "-XX:" + name;
        String value = body.substring(eq + 1);
        boolean safe = NUMERIC.matcher(value).matches()
                || KEYWORD_VALUES.getOrDefault(name, Set.of()).contains(value);
        return "-XX:" + name + "=" + (safe ? value : OMITTED);
    }

    /** "/opt/otel/opentelemetry-javaagent.jar=opts" -> "opentelemetry-javaagent.jar". */
    private static String agentFile(String spec) {
        String file = fileName(before(spec, '='));
        return FILE_NAME.matcher(file).matches() ? file : OMITTED;
    }

    /**
     * The main class or jar that javaArguments starts with: a jar reduced to
     * its file name, or a class name. Null for anything else — notably a jar
     * path containing a space, whose first token is only a fragment (such as
     * {@code /home/jane}) and must not be passed on.
     */
    static String mainClassOrJar(String javaArguments) {
        List<String> tokens = tokens(javaArguments);
        if (tokens.isEmpty()) return null;
        String first = tokens.get(0);
        if (first.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            String jar = fileName(first);
            return FILE_NAME.matcher(jar).matches() ? jar : null;
        }
        return CLASS_NAME.matcher(first).matches() ? first : null;
    }

    /** How many program arguments follow the main class or jar. */
    static int programArgumentCount(String javaArguments) {
        return Math.max(0, tokens(javaArguments).size() - 1);
    }

    private static List<String> tokens(String s) {
        var out = new ArrayList<String>();
        if (s == null) return out;
        for (String t : s.trim().split("\\s+")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(slash + 1);
    }

    private static String before(String s, char c) {
        int i = s.indexOf(c);
        return i < 0 ? s : s.substring(0, i);
    }

    private static int firstOf(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }
}
