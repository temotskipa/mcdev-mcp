package dev.mcdevmcp.mcp.tool.api;

@FunctionalInterface
public interface InputSchemaFactory {
    JsonObjectSchema generate(JsonType<?> type);
}
