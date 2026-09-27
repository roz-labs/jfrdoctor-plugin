package jfrdoc.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link McpServer}'s read loop, run by test/unit.sh. The
 * end-to-end protocol behaviour is covered by test/smoke.sh; this covers what
 * a real process can't reproduce deterministically.
 */
public final class McpServerTest {

    private static final List<String> failures = new ArrayList<>();
    private static int passed;

    public static void main(String[] args) throws Exception {
        // An Error thrown partway through reading a line (as OutOfMemoryError
        // can be under heap pressure) must not let the rest of that line be
        // parsed as a message of its own.
        String first = "x" + " ".repeat(50) + "{\"jsonrpc\":\"2.0\",\"id\":77,\"method\":\"ping\"}\n";
        String second = "{\"jsonrpc\":\"2.0\",\"id\":78,\"method\":\"ping\"}\n";
        byte[] input = (first + second).getBytes(StandardCharsets.UTF_8);

        var out = new ByteArrayOutputStream();
        new McpServer(List.of(), out).serve(new FailOnceStream(input, 10));
        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n");

        check(lines.length == 2, "one error for the interrupted line, one response for the next (got " + lines.length + ")");
        check(lines[0].contains("\"id\":null") && lines[0].contains("-32603"), "interrupted line answered once with an internal error");
        check(!out.toString(StandardCharsets.UTF_8).contains("\"id\":77"), "tail of the interrupted line is not run as a request");
        check(lines.length > 1 && lines[1].contains("\"id\":78") && lines[1].contains("\"result\":{}"), "the next line is served normally");

        if (!failures.isEmpty()) {
            System.err.println(failures.size() + " McpServer test(s) failed:");
            failures.forEach(f -> System.err.println("  " + f));
            System.exit(1);
        }
        System.out.println("McpServerTest: all " + passed + " checks passed.");
    }

    /** Serves {@code data}, throwing an OutOfMemoryError once when byte {@code failAt} is reached. */
    static final class FailOnceStream extends InputStream {
        private final byte[] data;
        private final int failAt;
        private int pos;
        private boolean failed;

        FailOnceStream(byte[] data, int failAt) {
            this.data = data;
            this.failAt = failAt;
        }

        @Override
        public int read() throws IOException {
            if (!failed && pos == failAt) {
                failed = true;
                throw new OutOfMemoryError("simulated");
            }
            return pos < data.length ? data[pos++] & 0xff : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            // One byte at a time, so the failure lands exactly where requested
            // even through the server's BufferedInputStream.
            if (len == 0) return 0;
            int c = read();
            if (c == -1) return -1;
            b[off] = (byte) c;
            return 1;
        }
    }

    static void check(boolean ok, String label) {
        if (ok) {
            passed++;
        } else {
            failures.add(label);
        }
    }
}
