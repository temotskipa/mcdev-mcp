package dev.mcdevmcp.tools.statictool;

import java.util.Objects;

/**
 * A classified wire value that does not retain the raw JSON representation.
 */
record TextArgument(ArgumentShape shape, String value) {
    TextArgument {
        Objects.requireNonNull(shape, "shape");
        if (shape == ArgumentShape.TEXT) {
            Objects.requireNonNull(value, "value");
        }
    }
    
    static TextArgument fromWire(Object value) {
        if (value == null) {
            return new TextArgument(ArgumentShape.MISSING, null);
        }
        if (value instanceof String text) {
            return new TextArgument(ArgumentShape.TEXT, text);
        }
        return new TextArgument(ArgumentShape.OTHER, String.valueOf(value));
    }
    
    boolean isText() {
        return shape == ArgumentShape.TEXT;
    }
    
    boolean isMissing() {
        return shape == ArgumentShape.MISSING;
    }
    
    String display() {
        return isMissing() ? "undefined" : value;
    }
}
