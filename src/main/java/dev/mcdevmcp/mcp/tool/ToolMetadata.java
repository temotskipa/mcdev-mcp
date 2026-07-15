package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.support.JsonValues;

import java.util.Map;

public record ToolMetadata(String name, String description, Map<String, Object> inputSchema) {
    public ToolMetadata {
        name = requireText(name, "Tool metadata name");
        description = requireText(description, "Tool metadata description");
        inputSchema = JsonValues.freezeMap(inputSchema);
    }
    
    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
