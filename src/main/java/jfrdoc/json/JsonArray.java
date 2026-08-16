package jfrdoc.json;

import java.util.ArrayList;
import java.util.List;

/** Minimal JSON array counterpart to {@link JsonObject}. */
public final class JsonArray {

    private final List<Object> values = new ArrayList<>();

    public JsonArray put(Object value) {
        values.add(value == null ? JsonObject.NULL : value);
        return this;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        JsonWriter.write(sb, this, 0, 0);
        return sb.toString();
    }

    List<Object> values() {
        return values;
    }
}
