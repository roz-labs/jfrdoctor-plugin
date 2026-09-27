package jfrdoc.json;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link JsonParser}, run by test/unit.sh. Plain main() with
 * no test framework, so the project keeps needing nothing but a JDK.
 */
public final class JsonParserTest {

    private static final List<String> failures = new ArrayList<>();
    private static int passed;

    public static void main(String[] args) {
        // Well-formed input.
        valid("{}", "{}");
        valid("[]", "[]");
        valid(" {\"a\" : [1, -2, 3.5, true, false, null, \"x\"]} ", "{\"a\":[1,-2,3.5,true,false,null,\"x\"]}");
        valid("\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t\"", "\"\\\" \\\\ / \\b \\f \\n \\r \\t\"");
        valid("\"\\u00e9\\uD83D\\uDE00\"", "\"é\uD83D\uDE00\"");
        valid("0", "0");
        valid("-0.5e+10", "-5000000000");
        valid("1E2", "100");
        valid(nest(JsonParser.MAX_DEPTH), nest(JsonParser.MAX_DEPTH));
        check(JsonParser.parse("9223372036854775807") instanceof Long, "max long stays a Long");
        check(JsonParser.parse("9223372036854775808") instanceof Double, "long overflow becomes a Double");
        check(JsonParser.parse("12") instanceof Long, "integer literal is a Long");
        check(JsonParser.parse("null") == JsonObject.NULL, "null literal is JsonObject.NULL");

        // Malformed input: every case must be a JsonException, nothing else.
        invalid("", "empty input");
        invalid("   ", "whitespace only");
        invalid("{", "unterminated object");
        invalid("{\"a\":1", "unterminated object after value");
        invalid("{\"a\":1,}", "trailing comma in object");
        invalid("[1,]", "trailing comma in array");
        invalid("{a:1}", "unquoted key");
        invalid("{\"a\" 1}", "missing colon");
        invalid("{\"a\":1 \"b\":2}", "missing comma");
        invalid("{\"a\":1,\"a\":2}", "duplicate key");
        invalid("{} {}", "two top-level values");
        invalid("{}x", "trailing garbage");
        invalid("\"abc", "unterminated string");
        invalid("\"a\u0001b\"", "raw control character in string");
        invalid("\"a\nb\"", "raw newline in string");
        invalid("\"\\x\"", "unknown escape");
        invalid("\"\\", "escape at end of input");
        invalid("\"\\u12\"", "short unicode escape");
        invalid("\"\\u12", "truncated unicode escape");
        invalid("\"\\uZZZZ\"", "non-hex unicode escape");
        invalid("\"\\u\uFF11\uFF12\uFF13\uFF14\"", "fullwidth digits in unicode escape");
        invalid("01", "leading zero");
        invalid("-", "bare minus");
        invalid("+1", "leading plus");
        invalid("1.", "fraction without digits");
        invalid(".5", "fraction without integer part");
        invalid("1e", "exponent without digits");
        invalid("NaN", "NaN");
        invalid("Infinity", "Infinity");
        invalid("tru", "truncated literal");
        invalid("nul", "truncated null");
        invalid("TRUE", "uppercase literal");
        invalid(nest(JsonParser.MAX_DEPTH + 1), "nesting one past the limit");
        invalid("[".repeat(100_000), "100k open brackets (stack exhaustion attempt)");
        invalid("{\"a\":".repeat(100_000), "100k nested objects (stack exhaustion attempt)");
        invalid("\u0000", "NUL byte");

        // Huge numbers must parse in linear time, not hang.
        long start = System.nanoTime();
        JsonParser.parse("1" + "0".repeat(500_000));
        check((System.nanoTime() - start) < 2_000_000_000L, "500k-digit integer parses in under 2 s");

        if (!failures.isEmpty()) {
            System.err.println(failures.size() + " JsonParser test(s) failed:");
            failures.forEach(f -> System.err.println("  " + f));
            System.exit(1);
        }
        System.out.println("JsonParserTest: all " + passed + " checks passed.");
    }

    static String nest(int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }

    static void valid(String json, String expectedCompact) {
        try {
            Object v = JsonParser.parse(json);
            String actual = v instanceof JsonObject o ? o.toString()
                    : v instanceof JsonArray a ? a.toString()
                    : render(v);
            check(actual.equals(expectedCompact), "parses " + abbreviate(json) + " (got " + abbreviate(actual) + ")");
        } catch (RuntimeException e) {
            check(false, "parses " + abbreviate(json) + " (threw " + e + ")");
        }
    }

    static String render(Object scalar) {
        var holder = new JsonArray().put(scalar).toString();
        return holder.substring(1, holder.length() - 1);
    }

    static void invalid(String json, String label) {
        try {
            JsonParser.parse(json);
            check(false, "rejects " + label + " (parsed without error)");
        } catch (JsonException e) {
            check(true, "rejects " + label);
        } catch (Throwable t) {
            check(false, "rejects " + label + " (threw " + t.getClass().getName() + " instead of JsonException)");
        }
    }

    static void check(boolean ok, String label) {
        if (ok) {
            passed++;
        } else {
            failures.add(label);
        }
    }

    static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 37) + "...";
    }
}
