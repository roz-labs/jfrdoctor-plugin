package jfrdoc.json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public double getDouble(String key) {
        if (get(key) instanceof Number n) return n.doubleValue();
        throw new JsonException("not a number: " + key);
    }

    /**
     * Adapts an MCP tool-call arguments map (as handed to us by the SDK's JSON
     * binding) into a JsonObject, so the nine Tool implementations keep reading
     * arguments through the same typed accessors regardless of transport.
     * A JSON null value is treated as absent, matching JSON Schema's "optional"
     * semantics rather than failing has()/get() on an explicit null.
     */
    @SuppressWarnings("unchecked")
    public static JsonObject fromMap(Map<String, Object> map) {
        var obj = new JsonObject();
        for (var entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value == null) continue;
            if (value instanceof Map<?, ?> nested) {
                obj.put(entry.getKey(), fromMap((Map<String, Object>) nested));
            } else if (value instanceof List<?> list) {
                var arr = new JsonArray();
                list.forEach(arr::put);
                obj.put(entry.getKey(), arr);
            } else {
                obj.put(entry.getKey(), value);
            }
        }
        return obj;
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
