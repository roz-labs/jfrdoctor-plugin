import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Starts the jfrdoc MCP server from source.
 *
 * <p>jfrdoc ships no compiled code. Claude Code runs
 * {@code java <plugin>/launcher/Launch.java <plugin>} (the JDK's single-file
 * source launcher), and this class compiles {@code <plugin>/src/main/java}
 * in memory with the JDK's own compiler, then calls
 * {@code jfrdoc.mcp.McpServer.main}. Nothing is downloaded and nothing is
 * written to disk; the only input is the plugin's own source tree.
 *
 * <p>Classes and resources resolve against the plugin tree only — never the
 * classpath, which defaults to the current directory — so files lying around
 * in the user's project can't shadow jfrdoc's own.
 *
 * <p>stdout belongs to the MCP protocol: everything here reports on stderr.
 */
public class Launch {

    public static void main(String[] args) throws Throwable {
        if (args.length != 1) {
            fail("usage: java launcher/Launch.java <plugin-root>");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path sources = root.resolve("src/main/java");
        Path resources = root.resolve("src/main/resources");
        if (!Files.isDirectory(sources)) {
            fail("jfrdoc: no sources at " + sources);
        }

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            fail("jfrdoc needs a full JDK 21+ (with javac), not a JRE.");
        }

        Map<String, byte[]> classes = compile(javac, sources);
        var loader = new SourceTreeClassLoader(classes, resources);
        Thread.currentThread().setContextClassLoader(loader);
        try {
            loader.loadClass("jfrdoc.mcp.McpServer")
                    .getMethod("main", String[].class)
                    .invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    static Map<String, byte[]> compile(JavaCompiler javac, Path sources) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(sources)) {
            files = walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .sorted()
                    .toList();
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager standard = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        // Resolve nothing from the classpath or a stray source path: every
        // jfrdoc type comes from the files passed in, everything else from the JDK.
        standard.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of());
        standard.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());

        var classes = new HashMap<String, byte[]>();
        var fileManager = new ForwardingJavaFileManager<JavaFileManager>(standard) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                       JavaFileObject.Kind kind, FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        return new ByteArrayOutputStream() {
                            @Override
                            public void close() {
                                classes.put(className, toByteArray());
                            }
                        };
                    }
                };
            }
        };

        var errors = new StringWriter();
        List<String> options = List.of("--release", "21", "-proc:none", "-encoding", "UTF-8", "-nowarn", "-g");
        boolean ok = javac.getTask(errors, fileManager, diagnostics, options, null,
                standard.getJavaFileObjectsFromPaths(new ArrayList<>(files))).call();
        if (!ok) {
            diagnostics.getDiagnostics().forEach(d -> System.err.println(d));
            System.err.print(errors);
            fail("jfrdoc: compiling " + files.size() + " source files failed.");
        }
        return classes;
    }

    /** Serves the in-memory classes and the plugin's resource folder; parent is the JDK itself. */
    static final class SourceTreeClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;
        private final Path resources;

        SourceTreeClassLoader(Map<String, byte[]> classes, Path resources) {
            super("jfrdoc", ClassLoader.getPlatformClassLoader());
            this.classes = classes;
            this.resources = resources;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }

        @Override
        protected URL findResource(String name) {
            Path file = resources.resolve(name).normalize();
            if (!file.startsWith(resources) || !Files.isRegularFile(file)) return null;
            try {
                return file.toUri().toURL();
            } catch (MalformedURLException e) {
                return null;
            }
        }
    }

    static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
