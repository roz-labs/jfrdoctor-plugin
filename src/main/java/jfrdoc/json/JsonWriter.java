package jfrdoc.json;

/** Serializes {@link JsonObject}/{@link JsonArray} trees, compact or indented. */
final class JsonWriter {

    private JsonWriter() {}

    static void write(StringBuilder sb, Object value, int indent, int depth) {
        switch (value) {
            case JsonObject o -> writeObject(sb, o, indent, depth);
            case JsonArray a -> writeArray(sb, a, indent, depth);
            case String s -> writeString(sb, s);
            case Double d -> sb.append(numberToString(d));
            case Float f -> sb.append(numberToString(f.doubleValue()));
            case Number n -> sb.append(n);
            case Boolean b -> sb.append(b);
            default -> sb.append("null"); // JsonObject.NULL and anything unknown
        }
    }

    static void writeObject(StringBuilder sb, JsonObject obj, int indent, int depth) {
        if (obj.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (var entry : obj.values().entrySet()) {
            if (!first) sb.append(',');
            first = false;
            newlineAndPad(sb, indent, depth + 1);
            writeString(sb, entry.getKey());
            sb.append(':');
            if (indent > 0) sb.append(' ');
            write(sb, entry.getValue(), indent, depth + 1);
        }
        newlineAndPad(sb, indent, depth);
        sb.append('}');
    }

    static void writeArray(StringBuilder sb, JsonArray arr, int indent, int depth) {
        if (arr.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        boolean first = true;
        for (var element : arr.values()) {
            if (!first) sb.append(',');
            first = false;
            newlineAndPad(sb, indent, depth + 1);
            write(sb, element, indent, depth + 1);
        }
        newlineAndPad(sb, indent, depth);
        sb.append(']');
    }

    static void newlineAndPad(StringBuilder sb, int indent, int depth) {
        if (indent == 0) return;
        sb.append('\n');
        sb.append(" ".repeat(indent * depth));
    }

    static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Doubles print without a trailing ".0" so ratios read as clean integers. */
    static String numberToString(double d) {
        if (!Double.isFinite(d)) return "null";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }
}
