package dev.mcdevmcp.mcp.tool.api;

import java.util.List;
import java.util.Objects;

public record ContentToolResult(List<ToolContent> content, boolean isError) implements ToolResult {
    public ContentToolResult {
        content = List.copyOf(Objects.requireNonNull(content, "content"));
    }
}
