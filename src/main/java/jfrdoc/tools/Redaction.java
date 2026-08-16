package jfrdoc.tools;

import java.util.regex.Pattern;

/**
 * Best-effort redaction for personal/secret data that can appear in freeform
 * strings surfaced by JFR tools: application exception messages, JVM/program
 * arguments, and file paths from the profiled application. This is
 * deliberately separate from the aggregate code-structure data (class and
 * method names) that is these tools' actual purpose and is never redacted —
 * only data that originates from the profiled application's runtime state
 * (what a user typed, what a system administrator named a host, what a
 * customer's file was called) is a candidate here.
 *
 * None of this is exhaustive: freeform text (exception messages in
 * particular) can carry PII in forms no regex catches. These patterns target
 * the common, well-defined shapes (email addresses, key=value secrets, URL
 * credentials, OS home-directory usernames, IPv4 addresses) and leave
 * everything else as-is.
 */
final class Redaction {

    private Redaction() {}

    private static final Pattern SECRET_KEY_VALUE = Pattern.compile(
            "((?:--|-D)?[\\w.-]*(?:password|secret|token|credential|api[_-]?key"
                    + "|private[_-]?key|access[_-]?key|auth)[\\w.-]*\\s*[:=]\\s*)(\\S+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern URL_USERINFO = Pattern.compile("://[^\\s/@:]+:[^\\s/@]+@");

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private static final Pattern HOME_DIR_UNIX = Pattern.compile("(/home/|/Users/)[^/]+");

    private static final Pattern HOME_DIR_WINDOWS = Pattern.compile("([A-Za-z]:\\\\Users\\\\)[^\\\\]+");

    private static final Pattern IPV4_ADDRESS = Pattern.compile(
            "\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.)\\d{1,3}\\b");

    /**
     * Redacts secret-shaped key=value pairs, URL userinfo credentials, and
     * email addresses from a freeform string. Used for JVM/program arguments
     * and application exception messages — both can carry any of these in
     * any position, not just where an earlier, narrower check used to look.
     */
    static String redactSecretsAndPii(String s) {
        if (s == null) return null;
        String out = SECRET_KEY_VALUE.matcher(s).replaceAll("$1[REDACTED]");
        out = URL_USERINFO.matcher(out).replaceAll("://[REDACTED]@");
        out = EMAIL.matcher(out).replaceAll("[REDACTED-EMAIL]");
        return out;
    }

    /**
     * Masks the username segment of an OS home-directory path
     * (/home/&lt;user&gt;/…, /Users/&lt;user&gt;/…, C:\Users\&lt;user&gt;\…).
     * Leaves the rest of the path — including the filename, which is this
     * tool's actual diagnostic payload — untouched. Does not scan the
     * filename itself for PII: free-form filenames are too unstructured to
     * pattern-match without excessive false positives.
     */
    static String redactHomeDirUser(String path) {
        if (path == null) return null;
        String out = HOME_DIR_UNIX.matcher(path).replaceAll("$1<redacted>");
        out = HOME_DIR_WINDOWS.matcher(out).replaceAll("$1<redacted>");
        return out;
    }

    /**
     * Masks the last octet of an IPv4 address (10.0.4.17 -&gt; 10.0.4.xxx).
     * Deliberately NOT applied to hostnames — those are the tool's actual
     * diagnostic signal (which service is slow) and are organizational
     * infrastructure info, not personal data.
     */
    static String maskIpLastOctet(String address) {
        if (address == null) return null;
        return IPV4_ADDRESS.matcher(address).replaceAll("$1xxx");
    }
}
