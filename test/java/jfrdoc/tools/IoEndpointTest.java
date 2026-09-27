package jfrdoc.tools;

import java.util.ArrayList;
import java.util.List;

/** Unit tests for jfr_io's endpoint naming, run by test/unit.sh. */
public final class IoEndpointTest {

    private static final List<String> failures = new ArrayList<>();
    private static int passed;

    public static void main(String[] args) {
        // JFR's host is the IP literal itself when reverse DNS has no answer.
        check("10.0.4.xxx:5432".equals(JfrIoTool.endpointKey("10.0.4.17", "10.0.4.17", 5432)),
                "IP-literal host is masked like the address field");
        check("10.0.4.xxx:5432".equals(JfrIoTool.endpointKey("", "10.0.4.17", 5432)),
                "address fallback is masked");
        check("db.internal:5432".equals(JfrIoTool.endpointKey("db.internal", "10.0.4.17", 5432)),
                "hostnames are kept (they are the diagnostic signal)");
        check("10.0.4.xxx".equals(JfrIoTool.endpointKey(null, "10.0.4.17", 51234)),
                "ephemeral port dropped, address still masked");
        check("unknown".equals(JfrIoTool.endpointKey(null, null, null)), "no host or address -> unknown");

        if (!failures.isEmpty()) {
            System.err.println(failures.size() + " IoEndpoint test(s) failed:");
            failures.forEach(f -> System.err.println("  " + f));
            System.exit(1);
        }
        System.out.println("IoEndpointTest: all " + passed + " checks passed.");
    }

    static void check(boolean ok, String label) {
        if (ok) {
            passed++;
        } else {
            failures.add(label);
        }
    }
}
