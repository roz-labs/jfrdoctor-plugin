package jfrdoc.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Buckets a fully-qualified class name into user_code / framework / jdk using
 * package-prefix lists shipped as classpath resources (/frameworks/*.txt),
 * so the jar is self-contained regardless of working directory.
 */
public record FrameworkCategorizer(
        Set<String> alwaysFramework,
        Set<String> extraFramework,
        Set<String> jdk) {

    public FrameworkCategorizer {
        alwaysFramework = Set.copyOf(alwaysFramework);
        extraFramework = Set.copyOf(extraFramework);
        jdk = Set.copyOf(jdk);
    }

    public String categorize(String fqcn) {
        if (fqcn == null) {
            throw new IllegalArgumentException(
                    "fqcn must not be null; callers should route unresolved samples to sample_quality, not categorize()");
        }
        if (fqcn.startsWith("org.springframework.samples.petclinic")) {
            // PetClinic shares Spring Framework's package prefix but is user code.
            return "user_code";
        }
        if (startsWithAny(fqcn, jdk)) return "jdk";
        if (startsWithAny(fqcn, alwaysFramework)) return "framework";
        if (startsWithAny(fqcn, extraFramework)) return "framework";
        return "user_code";
    }

    public static FrameworkCategorizer forFramework(String framework) throws IOException {
        Set<String> common = loadPrefixes("framework-common");
        Set<String> jdk = loadPrefixes("jdk");
        Set<String> extra = loadPrefixesIfExists(framework);
        return new FrameworkCategorizer(common, extra, jdk);
    }

    static Set<String> loadPrefixes(String name) throws IOException {
        var lines = readResource(name);
        if (lines == null) {
            throw new IOException("framework definition not found on classpath: /frameworks/" + name + ".txt");
        }
        return parsePrefixes(lines);
    }

    static Set<String> loadPrefixesIfExists(String name) throws IOException {
        if (name == null || name.isBlank()) return Set.of();
        var lines = readResource(name);
        return lines == null ? Set.of() : parsePrefixes(lines);
    }

    static List<String> readResource(String name) throws IOException {
        var stream = FrameworkCategorizer.class.getResourceAsStream("/frameworks/" + name + ".txt");
        if (stream == null) return null;
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }

    static Set<String> parsePrefixes(List<String> lines) {
        var set = new LinkedHashSet<String>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            set.add(trimmed);
        }
        return set;
    }

    static boolean startsWithAny(String s, Set<String> prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) return true;
        }
        return false;
    }
}
