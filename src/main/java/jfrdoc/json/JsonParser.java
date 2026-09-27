package jfrdoc.json;

/**
 * Strict RFC 8259 parser for the MCP wire protocol — one JSON-RPC message per
 * line, read from a client over stdin. It is deliberately defensive, because
 * a parser failure other than a clean error would take the whole server
 * down. Every failure is a {@link JsonException}; nothing else may escape
 * {@link #parse}, whatever the input:
 *
 * <ul>
 *   <li>nesting is capped at {@link #MAX_DEPTH}, so adversarial {@code [[[[…}
 *       can't overflow the stack;</li>
 *   <li>unicode (backslash-u) escapes need exactly four hex digits, and raw
 *       control characters inside strings are rejected;</li>
 *   <li>duplicate object keys are rejected, so a request can't carry two
 *       conflicting {@code method} or {@code id} members;</li>
 *   <li>numbers are validated against the JSON grammar before conversion, and
 *       converted in linear time (no BigInteger/BigDecimal on huge literals);</li>
 *   <li>anything but whitespace after the top-level value is an error.</li>
 * </ul>
 *
 * Values map to: {@link JsonObject}, {@link JsonArray}, {@link String},
 * {@link Long} (integers that fit), {@link Double} (everything else numeric),
 * {@link Boolean}, and {@link JsonObject#NULL}. Input length is bounded by the
 * caller (the server caps line length before parsing).
 */
public final class JsonParser {

    public static final int MAX_DEPTH = 64;

    private final String s;
    private int pos;

    private JsonParser(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        if (text == null) throw new JsonException("no input");
        var p = new JsonParser(text);
        p.skipWhitespace();
        Object value = p.readValue(0);
        p.skipWhitespace();
        if (p.pos != text.length()) throw p.error("unexpected trailing content");
        return value;
    }

    private Object readValue(int depth) {
        if (pos >= s.length()) throw error("unexpected end of input");
        char c = s.charAt(pos);
        return switch (c) {
            case '{' -> readObject(depth + 1);
            case '[' -> readArray(depth + 1);
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", JsonObject.NULL);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) yield readNumber();
                throw error("unexpected character");
            }
        };
    }

    private JsonObject readObject(int depth) {
        if (depth > MAX_DEPTH) throw error("nesting deeper than " + MAX_DEPTH);
        pos++; // '{'
        var obj = new JsonObject();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return obj;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') throw error("expected object key");
            String key = readString();
            if (obj.has(key)) throw error("duplicate object key");
            skipWhitespace();
            expect(':');
            skipWhitespace();
            obj.put(key, readValue(depth));
            skipWhitespace();
            char c = next();
            if (c == '}') return obj;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private JsonArray readArray(int depth) {
        if (depth > MAX_DEPTH) throw error("nesting deeper than " + MAX_DEPTH);
        pos++; // '['
        var arr = new JsonArray();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return arr;
        }
        while (true) {
            skipWhitespace();
            arr.put(readValue(depth));
            skipWhitespace();
            char c = next();
            if (c == ']') return arr;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }

    private String readString() {
        pos++; // opening quote
        var sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) throw error("unterminated string");
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c < 0x20) throw error("unescaped control character in string");
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) throw error("unterminated escape");
            char e = s.charAt(pos++);
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> sb.append(readHex4());
                default -> throw error("invalid escape");
            }
        }
    }

    private char readHex4() {
        if (pos + 4 > s.length()) throw error("truncated unicode escape");
        int v = 0;
        for (int i = 0; i < 4; i++) {
            int d = Character.digit(s.charAt(pos + i), 16);
            // Character.digit accepts non-ASCII digits (e.g. fullwidth);
            // JSON allows only [0-9A-Fa-f].
            if (d < 0 || s.charAt(pos + i) > 'f') throw error("invalid unicode escape");
            v = (v << 4) | d;
        }
        pos += 4;
        return (char) v;
    }

    private Object readNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        if (peek() == '0') {
            pos++;
        } else if (isDigit(peek())) {
            while (isDigit(peek())) pos++;
        } else {
            throw error("invalid number");
        }
        boolean integral = true;
        if (peek() == '.') {
            integral = false;
            pos++;
            if (!isDigit(peek())) throw error("invalid number");
            while (isDigit(peek())) pos++;
        }
        if (peek() == 'e' || peek() == 'E') {
            integral = false;
            pos++;
            if (peek() == '+' || peek() == '-') pos++;
            if (!isDigit(peek())) throw error("invalid number");
            while (isDigit(peek())) pos++;
        }
        String literal = s.substring(start, pos);
        if (integral) {
            try {
                return Long.parseLong(literal);
            } catch (NumberFormatException overflow) {
                // Grammar already validated; fall through to a lossy double
                // rather than an unbounded-cost BigInteger.
            }
        }
        return Double.parseDouble(literal);
    }

    private Object readLiteral(String word, Object value) {
        if (!s.startsWith(word, pos)) throw error("invalid literal");
        pos += word.length();
        return value;
    }

    private void skipWhitespace() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            pos++;
        }
    }

    /** Current char, or NUL at end of input (never a valid token start). */
    private char peek() {
        return pos < s.length() ? s.charAt(pos) : '\0';
    }

    private char next() {
        if (pos >= s.length()) throw error("unexpected end of input");
        return s.charAt(pos++);
    }

    private void expect(char c) {
        if (next() != c) throw error("expected '" + c + "'");
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** Position only, never input text: error messages must not echo client data. */
    private JsonException error(String what) {
        return new JsonException(what + " at offset " + pos);
    }
}
