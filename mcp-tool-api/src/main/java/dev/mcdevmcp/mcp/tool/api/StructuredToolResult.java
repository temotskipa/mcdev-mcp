package dev.mcdevmcp.mcp.tool.api;

import java.util.List;
import java.util.Objects;

public record StructuredToolResult<T>(List<ToolContent> content, JsonType<T> structuredType, T structuredContent, boolean isError) implements ToolResult {
    public StructuredToolResult {
        content = List.copyOf(Objects.requireNonNull(content, "content"));
        Objects.requireNonNull(structuredType, "structuredType");
        Objects.requireNonNull(structuredContent, "structuredContent");
    }
}
