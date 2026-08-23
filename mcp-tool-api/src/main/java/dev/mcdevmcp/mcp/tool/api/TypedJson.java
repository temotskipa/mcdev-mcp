package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.util.Objects;

public record TypedJson<T>(Object value, JsonType<T> targetType) {
    public TypedJson {
        Objects.requireNonNull(targetType, "targetType");
    }

    public static <T> TypedJson<T> of(Object value, Class<T> targetType) {
        return new TypedJson<>(value, JsonType.of(targetType));
    }

    public static <T> TypedJson<T> of(Object value, TypeRef<T> targetType) {
        return new TypedJson<>(value, JsonType.of(targetType));
    }

    public T decode(McpJsonMapper mapper) {
        return targetType.decode(mapper, value);
    }
}
