package jfrdoc.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Minimal ordered JSON object. Covers exactly the surface the jfrdoc tools and
 * the MCP layer need — not a general-purpose JSON library.
 */
public final class JsonObject {

    /** Sentinel that serializes to JSON null (a Java null means "absent"). */
    public static final Object NULL = new Object() {
        @Override public String toString() { return "null"; }
    };

    private final Map<String, Object> values = new LinkedHashMap<>();

    public JsonObject put(String key, Object value) {
        if (value != null) values.put(key, value);
        return this;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Object get(String key) {
        var v = values.get(key);
        if (v == null) throw new JsonException("missing key: " + key);
        return v;
    }

    public String getString(String key) {
        if (get(key) instanceof String s) return s;
        throw new JsonException("not a string: " + key);
    }

    public int getInt(String key) {
        if (get(key) instanceof Number n) return n.intValue();
        throw new JsonException("not a number: " + key);
    }

    public long getLong(String key) {
        if (get(key) instanceof Number n) return n.longValue();
        throw new JsonException("not a number: " + key);
    }

    /** The value for {@code key}, or Java null when absent (JSON null is {@link #NULL}). */
    public Object opt(String key) {
        return values.get(key);
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(values.keySet());
    }

    /**
     * A shallow copy without JSON-null members. Tool arguments go through this
     * so an explicit {@code "top_n": null} reads as "not given", matching JSON
     * Schema's optional semantics rather than failing a typed accessor.
     */
    public JsonObject withoutNulls() {
        var copy = new JsonObject();
        values.forEach((k, v) -> {
            if (v != NULL) copy.put(k, v);
        });
        return copy;
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        JsonWriter.write(sb, this, 0, 0);
        return sb.toString();
    }

    public String toString(int indent) {
        var sb = new StringBuilder();
        JsonWriter.write(sb, this, indent, 0);
        return sb.toString();
    }

    Map<String, Object> values() {
        return values;
    }
}
