package jfrdoc.tools;

import java.util.List;

import jfrdoc.json.JsonArray;
import jfrdoc.json.JsonObject;

/**
 * A single JFR analysis capability: name + description + JSON-Schema input
 * contract + execution. The MCP layer exposes each Tool verbatim.
 */
public interface Tool {

    String toolName();

    String description();

    /** JSON Schema (draft-07 style object schema) describing {@link #execute} input. */
    String inputSchema();

    /** Runs the tool; returns pretty-printed JSON, or a string starting with "Error:". */
    String execute(JsonObject input);

    /**
     * Declarative input-schema builder. Field names come from an enum so tool
     * code and schema can never drift apart.
     */
    record Prop<E extends Enum<E>>(E name, String type, String description,
                                   List<String> enumValues, boolean required) {

        public static <E extends Enum<E>> Prop<E> string(E name, String description) {
            return new Prop<>(name, "string", description, List.of(), true);
        }

        public static <E extends Enum<E>> Prop<E> stringEnum(E name, String description, String... values) {
            return new Prop<>(name, "string", description, List.of(values), true);
        }

        public static <E extends Enum<E>> Prop<E> integer(E name, String description) {
            return new Prop<>(name, "integer", description, List.of(), true);
        }

        public static <E extends Enum<E>> Prop<E> number(E name, String description) {
            return new Prop<>(name, "number", description, List.of(), true);
        }

        public Prop<E> optional() {
            return new Prop<>(name, type, description, enumValues, false);
        }
    }

    static String schema(Prop<?>... props) {
        var properties = new JsonObject();
        var required = new JsonArray();
        for (var prop : props) {
            var spec = new JsonObject()
                    .put("type", prop.type())
                    .put("description", prop.description());
            if (!prop.enumValues().isEmpty()) {
                var allowed = new JsonArray();
                prop.enumValues().forEach(allowed::put);
                spec.put("enum", allowed);
            }
            properties.put(prop.name().name(), spec);
            if (prop.required()) required.put(prop.name().name());
        }
        var schema = new JsonObject()
                .put("type", "object")
                .put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema.toString();
    }
}
