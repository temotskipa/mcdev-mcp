package dev.mcdevmcp.mcp.tool.api;

import java.util.*;

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
        if (value == null || value instanceof String || value instanceof Boolean || isJsonNumber(value)) {
            return value;
        }
        if (value instanceof Map<?, ?> object) {
            Map<String, Object> copy = new LinkedHashMap<>();
            object.forEach((key, entryValue) -> copy.put((String) key, immutableValue(entryValue)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> array) {
            List<Object> copy = new ArrayList<>(array.size());
            array.forEach(entryValue -> copy.add(immutableValue(entryValue)));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("Schema values must be JSON tree nodes, got: " + value.getClass().getTypeName());
    }

    private static boolean isJsonNumber(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal || value instanceof Float fFloatingPoint && Float.isFinite(fFloatingPoint) || value instanceof Double dFloatingPoint && Double.isFinite(dFloatingPoint);
    }
}