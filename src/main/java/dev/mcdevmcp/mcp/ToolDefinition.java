package dev.mcdevmcp.mcp;

import com.google.gson.JsonObject;

import java.util.Objects;

public record ToolDefinition(String name, String description, JsonObject inputSchema, ToolHandler handler, ToolAvailability availability) {
    public ToolDefinition {
        name = requireText(name, "Tool name");
        description = requireText(description, "Tool description");
        inputSchema = Objects.requireNonNull(inputSchema, "Tool input schema").deepCopy();
        Objects.requireNonNull(handler, "Tool handler");
        Objects.requireNonNull(availability, "Tool availability");
    }
    
    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
    
    @Override
    public JsonObject inputSchema() {
        return inputSchema.deepCopy();
    }
}
