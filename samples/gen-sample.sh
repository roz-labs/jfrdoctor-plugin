#!/usr/bin/env bash
# Generates samples/sample.jfr: a ~30-second multi-threaded workload recorded
# with the JFR "profile" settings, covering all nine jfrdoc tools with real
# (not near-empty) data — CPU-bound compute, allocation churn, synchronized-
# monitor contention, blocking-queue parking, a slow loopback socket
# exchange, and exceptions (with jdk.JavaExceptionThrow explicitly enabled,
# since JFR's stock profile/default settings disable it — see jfr_exceptions'
# event_availability field). Requires JDK 21+ on PATH.
#
# An earlier version of this script used Java's single-file source-launch
# mode (`java Foo.java`), which runs under the javac launcher and pollutes
# jvmArguments/javaArguments with launcher internals instead of a normal
# app — and used one thread doing only allocation, giving almost no CPU
# samples. This version compiles then runs normally, with a dedicated
# always-on-CPU thread so jdk.ExecutionSample has enough density to be
# useful in the demo report.
set -euo pipefail
cd "$(dirname "$0")"

WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

cat > "$WORKDIR/JfrLoad.java" <<'EOF'
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class JfrLoad {

    static final Object sharedLock = new Object();

    public static void main(String[] args) throws Exception {
        System.out.println("Generating load for JFR recording...");
        long deadlineMs = System.currentTimeMillis() + 30_000;

        BlockingQueue<Integer> queue = new LinkedBlockingQueue<>(2);

        Thread producer = new Thread(() -> producerLoop(queue, deadlineMs), "producer");
        List<Thread> consumers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            consumers.add(new Thread(() -> consumerLoop(queue, deadlineMs), "consumer-" + i));
        }
        List<Thread> lockers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            lockers.add(new Thread(() -> lockContentionLoop(deadlineMs), "locker-" + i));
        }
        // Dedicated, unthrottled CPU-bound thread: every other thread here
        // spends most of its wall time blocked (queue, lock, I/O, sleep), so
        // without a thread that is ALWAYS on-CPU, jdk.ExecutionSample barely
        // fires. This one never sleeps or blocks until the deadline.
        Thread cpuBurn = new Thread(() -> cpuBurnLoop(deadlineMs), "cpu-burn");
        Thread exceptionThread = new Thread(() -> exceptionLoop(deadlineMs), "exception-worker");

        ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        int port = server.getLocalPort();
        Thread ioServer = new Thread(() -> ioServerLoop(server, deadlineMs), "io-server");
        Thread ioClient = new Thread(() -> ioClientLoop(port, deadlineMs), "io-client");

        producer.start();
        consumers.forEach(Thread::start);
        lockers.forEach(Thread::start);
        cpuBurn.start();
        exceptionThread.start();
        ioServer.start();
        ioClient.start();

        // Bounded joins: every loop above self-terminates at deadlineMs, but
        // give each a generous grace period rather than risk this script
        // hanging forever in CI if something unexpected blocks.
        long joinTimeoutMs = 10_000;
        producer.join(joinTimeoutMs);
        for (Thread c : consumers) c.join(joinTimeoutMs);
        cpuBurn.join(joinTimeoutMs);
        for (Thread l : lockers) l.join(joinTimeoutMs);
        exceptionThread.join(joinTimeoutMs);
        ioClient.join(joinTimeoutMs);
        ioServer.join(joinTimeoutMs);

        System.out.println("Load complete.");
    }

    static void producerLoop(BlockingQueue<Integer> queue, long deadlineMs) {
        // offer() with a bound, not put(): put() can block forever if every
        // consumer has already exited (their own deadline-check loops can
        // race ahead of this one) and nothing is left to drain the queue.
        long n = 0;
        var garbage = new ArrayList<byte[]>();
        while (System.currentTimeMillis() < deadlineMs) {
            try {
                queue.offer((int) (n % 100_000), 200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            garbage.add(new byte[16 * 1024]);
            if (garbage.size() > 512) garbage.clear();
            n++;
        }
    }

    static void consumerLoop(BlockingQueue<Integer> queue, long deadlineMs) {
        var index = new HashMap<Integer, String>();
        while (System.currentTimeMillis() < deadlineMs) {
            try {
                Integer v = queue.poll(50, TimeUnit.MILLISECONDS);
                if (v == null) continue;
                long h = fibonacci(20 + (v % 5));
                index.put(v, "entry-" + h);
                if (index.size() > 5000) index.clear();
                Thread.sleep(20); // slow consumer -> producer genuinely blocks on the size-2 queue
            } catch (InterruptedException ignored) {
            }
        }
    }

    static long fibonacci(int n) {
        if (n <= 1) return n;
        return fibonacci(n - 1) + fibonacci(n - 2);
    }

    // Never sleeps, never blocks — the one thread guaranteed to be on-CPU for
    // the whole recording, so jdk.ExecutionSample has something to sample.
    static void cpuBurnLoop(long deadlineMs) {
        long acc = 0;
        while (System.currentTimeMillis() < deadlineMs) {
            acc += fibonacci(28);
        }
        if (acc == Long.MIN_VALUE) System.out.println(acc); // defeat dead-code elimination
    }

    // Real synchronized-monitor contention: 2 threads competing for one lock,
    // each holding it long enough (10ms) that the other measurably waits, with
    // a gap outside the lock so contention is a visible signal, not the
    // entire recording — 4 threads hammering with no gap starved every other
    // signal (CPU samples included) down to near-zero during tuning.
    static void lockContentionLoop(long deadlineMs) {
        while (System.currentTimeMillis() < deadlineMs) {
            synchronized (sharedLock) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                }
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
            }
        }
    }

    static void exceptionLoop(long deadlineMs) {
        int i = 0;
        int[] tiny = new int[2];
        while (System.currentTimeMillis() < deadlineMs) {
            try {
                switch (i % 3) {
                    case 0 -> Integer.parseInt("not-a-number-" + i);
                    case 1 -> { int x = tiny[i % 5]; }
                    default -> throw new IllegalStateException("synthetic-" + i);
                }
            } catch (Exception e) {
                // swallow — just generating exception-throw events for the demo
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            i++;
        }
    }

    // Loopback socket with a deliberate server-side delay, so the client's
    // SocketRead genuinely blocks past JFR's ~10ms I/O threshold — real disk
    // I/O on a fast/page-cached filesystem is too quick to rely on for this.
    static void ioServerLoop(ServerSocket server, long deadlineMs) {
        try {
            server.setSoTimeout(2000);
            try (Socket s = server.accept();
                 var in = new DataInputStream(s.getInputStream());
                 var out = new DataOutputStream(s.getOutputStream())) {
                s.setSoTimeout(2000);
                while (System.currentTimeMillis() < deadlineMs) {
                    int req = in.readInt();
                    Thread.sleep(15);
                    out.writeInt(req);
                    out.flush();
                }
            }
        } catch (Exception e) {
            // deadline reached mid-exchange, or the 2s guard timeout fired — fine, this is a demo script
        }
    }

    static void ioClientLoop(int port, long deadlineMs) {
        try (Socket s = new Socket(java.net.InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(2000);
            var out = new DataOutputStream(s.getOutputStream());
            var in = new DataInputStream(s.getInputStream());
            int i = 0;
            while (System.currentTimeMillis() < deadlineMs) {
                out.writeInt(i);
                out.flush();
                in.readInt();
                i++;
            }
        } catch (Exception e) {
            // deadline reached mid-exchange, or the 2s guard timeout fired — fine, this is a demo script
        }
    }
}
EOF

javac -d "$WORKDIR" "$WORKDIR/JfrLoad.java"

# jdk.InitialEnvironmentVariable, jdk.SystemProcess and jdk.InitialSystemProperty
# are disabled explicitly. JFR's "profile" settings record all three for the whole
# machine, not just this workload: environment variables with their VALUES, and the
# full command line of every process on the host. A recording made on a developer
# box or in CI therefore carries credentials, tokens and private paths that have
# nothing to do with the demo — an earlier version of this sample leaked exactly
# that. None of jfrdoc's nine tools read these three event types (they take JVM
# arguments from jdk.JVMInformation instead), so turning them off costs nothing.
# ci/check-sample-privacy.sh enforces that they stay off.
java -XX:NativeMemoryTracking=summary \
     -XX:StartFlightRecording=duration=30s,filename="$PWD/sample.jfr",settings=profile,jdk.JavaExceptionThrow#enabled=true,jdk.InitialEnvironmentVariable#enabled=false,jdk.SystemProcess#enabled=false,jdk.InitialSystemProperty#enabled=false \
     -cp "$WORKDIR" JfrLoad

echo "Wrote $PWD/sample.jfr"
