package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.McpJsonMapper;

import java.util.Map;
import java.util.Objects;

public record ToolInput<A>(JsonType<A> type, JsonObjectSchema schema) {
    public ToolInput {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(schema, "schema");
    }

    public static <A> ToolInput<A> of(Class<A> type, InputSchemaFactory factory) {
        JsonType<A> jsonType = JsonType.of(Objects.requireNonNull(type, "type"));
        return new ToolInput<>(jsonType, Objects.requireNonNull(factory, "factory").generate(jsonType));
    }

    public A decode(McpJsonMapper mapper, Map<String, Object> arguments) {
        return type.decode(Objects.requireNonNull(mapper, "mapper"), Objects.requireNonNull(arguments, "arguments"));
    }
}
