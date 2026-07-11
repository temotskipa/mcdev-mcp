package dev.mcdevmcp.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JsonValues {
    private JsonValues() {
    }

    public static Object freeze(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Double number && !Double.isFinite(number)) {
            throw new IllegalArgumentException("JSON numbers must be finite");
        }
        if (value instanceof Float number && !Float.isFinite(number)) {
            throw new IllegalArgumentException("JSON numbers must be finite");
        }
        if (value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            var frozen = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                frozen.put(key, freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(frozen);
        }
        if (value instanceof List<?> list) {
            var frozen = new ArrayList<>(list.size());
            for (Object item : list) {
                frozen.add(freeze(item));
            }
            return Collections.unmodifiableList(frozen);
        }
        throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass().getName());
    }

    public static Map<String, Object> freezeMap(Map<String, ?> values) {
        Objects.requireNonNull(values, "JSON object");
        return asMap(freeze(values));
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put((String) entry.getKey(), entry.getValue());
            }
            return Collections.unmodifiableMap(result);
        }
        throw new IllegalStateException("Frozen JSON object is not a map");
    }

}
