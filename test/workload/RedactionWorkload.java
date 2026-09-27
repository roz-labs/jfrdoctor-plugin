import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Workload for test/redaction.sh: produces, under JFR, every kind of data
 * SECURITY.md promises to keep out of tool output — secrets and an email in
 * exception messages, files under a home directory, socket traffic to a raw
 * IPv4 address — each carrying the marker strings the test then searches
 * every tool's output for.
 *
 * Usage: java RedactionWorkload <file-under-a-home-directory>
 */
public class RedactionWorkload {

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[64 * 1024]);

        long deadline = System.currentTimeMillis() + 3_000;
        try (var server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0));
            Thread echo = new Thread(() -> echo(server), "echo-server");
            echo.setDaemon(true);
            echo.start();

            try (var client = new Socket()) {
                client.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), server.getLocalPort()));
                OutputStream out = client.getOutputStream();
                InputStream in = client.getInputStream();
                byte[] buf = new byte[512];
                int round = 0;
                while (System.currentTimeMillis() < deadline) {
                    // File I/O under the home directory.
                    Files.readAllBytes(file);
                    // Socket I/O to a raw IPv4 address.
                    out.write(buf);
                    out.flush();
                    in.readNBytes(buf.length);
                    // Exceptions whose messages carry secrets, an email and URL credentials.
                    try {
                        throw new IllegalStateException("login failed for alice.secret@example.com password=hunter2"
                                + " url=https://bob:s3cretpw@db.example.com/prod " + "x".repeat(200) + round);
                    } catch (IllegalStateException expected) {
                        round++;
                    }
                    Thread.sleep(5);
                }
            }
        }
        System.out.println("workload done");
    }

    static void echo(ServerSocket server) {
        try (Socket s = server.accept()) {
            byte[] buf = new byte[512];
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            while (true) {
                byte[] got = in.readNBytes(buf.length);
                if (got.length == 0) return;
                Thread.sleep(2);
                out.write(got);
                out.flush();
            }
        } catch (Exception ignored) {
            // client closed
        }
    }
}
