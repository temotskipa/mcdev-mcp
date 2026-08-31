package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.McpJsonMapper;

import java.util.Map;
import java.util.Objects;

public final class ToolInput<A> {
    private final JsonType<A> type;
    private final JsonObjectSchema schema;

    private ToolInput(JsonType<A> type, JsonObjectSchema schema) {
        this.type = Objects.requireNonNull(type, "type");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    public static <A> ToolInput<A> of(Class<A> type, InputSchemaFactory factory) {
        JsonType<A> jsonType = JsonType.of(Objects.requireNonNull(type, "type"));
        return new ToolInput<>(jsonType, Objects.requireNonNull(factory, "factory").generate(jsonType));
    }

    public JsonType<A> type() {
        return type;
    }

    public JsonObjectSchema schema() {
        return schema;
    }

    public A decode(McpJsonMapper mapper, Map<String, Object> arguments) {
        return type.decode(Objects.requireNonNull(mapper, "mapper"), Objects.requireNonNull(arguments, "arguments"));
    }
}
