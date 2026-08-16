package jfrdoc.json;

/** Thrown on malformed JSON input or type-mismatched access. */
public final class JsonException extends RuntimeException {

    public JsonException(String message) {
        super(message);
    }
}
