package jfrdoc.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link Redaction}, run by test/unit.sh: every shape
 * SECURITY.md promises to mask, on every OS path layout it names.
 * test/redaction.sh checks the same promises end to end on a real recording.
 */
public final class RedactionTest {

    private static final List<String> failures = new ArrayList<>();
    private static int passed;

    public static void main(String[] args) {
        // Exception messages: emails, key=value secrets, URL userinfo.
        String msg = Redaction.redactSecretsAndPii(
                "user jane.doe@corp.example failed: password=hunter2 api_key: abc123 token=t0k "
                        + "db=jdbc:postgresql://svc:pw9@db.internal/x --secret=s3 auth=Bearer");
        for (String leaked : List.of("jane.doe@corp.example", "hunter2", "abc123", "t0k", "svc:pw9", "s3", "Bearer")) {
            check(!msg.contains(leaked), "exception message hides '" + leaked + "' (got " + msg + ")");
        }
        check(msg.contains("[REDACTED-EMAIL]") && msg.contains("://[REDACTED]@db.internal"),
                "redaction markers keep the message readable");
        check(Redaction.redactSecretsAndPii(null) == null, "null message stays null");
        check("plain text".equals(Redaction.redactSecretsAndPii("plain text")), "text without secrets is unchanged");

        // Home-directory usernames on Linux, macOS and Windows.
        check("/home/<redacted>/app/data.bin".equals(Redaction.redactHomeDirUser("/home/alice/app/data.bin")),
                "Linux home-directory user masked");
        check("/Users/<redacted>/Library/x.db".equals(Redaction.redactHomeDirUser("/Users/bob/Library/x.db")),
                "macOS home-directory user masked");
        check("C:\\Users\\<redacted>\\AppData\\cfg.xml".equals(Redaction.redactHomeDirUser("C:\\Users\\carol\\AppData\\cfg.xml")),
                "Windows home-directory user masked");
        check("/var/lib/app/data.bin".equals(Redaction.redactHomeDirUser("/var/lib/app/data.bin")),
                "paths outside a home directory are unchanged");

        // IPv4 last octet; hostnames untouched.
        check("10.0.4.xxx".equals(Redaction.maskIpLastOctet("10.0.4.17")), "IPv4 last octet masked");
        check("db.internal".equals(Redaction.maskIpLastOctet("db.internal")), "hostname unchanged");

        if (!failures.isEmpty()) {
            System.err.println(failures.size() + " Redaction test(s) failed:");
            failures.forEach(f -> System.err.println("  " + f));
            System.exit(1);
        }
        System.out.println("RedactionTest: all " + passed + " checks passed.");
    }

    static void check(boolean ok, String label) {
        if (ok) {
            passed++;
        } else {
            failures.add(label);
        }
    }
}
