package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.support.JsonValues;

import java.util.Map;
import java.util.Objects;

public record ToolDefinition(String name, String description, Map<String, Object> inputSchema, ToolBinding<?> binding, ToolAvailability availability) {
    public ToolDefinition {
        name = requireText(name, "Tool name");
        description = requireText(description, "Tool description");
        inputSchema = JsonValues.freezeMap(inputSchema);
        Objects.requireNonNull(binding, "Tool binding");
        Objects.requireNonNull(availability, "Tool availability");
    }
    
    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
