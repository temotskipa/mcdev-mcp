package dev.mcdevmcp.mcp.tool.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record JsonObjectSchema(Map<String, Object> value) {
    public JsonObjectSchema {
        value = immutableObject(value);
        if (!"object".equals(value.get("type"))) {
            throw new IllegalArgumentException("JSON Schema root type must be object");
        }
    }

    public static JsonObjectSchema of(Map<String, Object> value) {
        return new JsonObjectSchema(value);
    }

    private static Map<String, Object> immutableObject(Map<String, Object> value) {
        Objects.requireNonNull(value, "value");
        Map<String, Object> copy = new LinkedHashMap<>();
        value.forEach((key, entryValue) -> copy.put(Objects.requireNonNull(key, "schema key"), immutableValue(entryValue)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> object) {
            Map<String, Object> copy = new LinkedHashMap<>();
            object.forEach((key, entryValue) -> copy.put(String.class.cast(key), immutableValue(entryValue)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> array) {
            List<Object> copy = new ArrayList<>(array.size());
            array.forEach(entryValue -> copy.add(immutableValue(entryValue)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
