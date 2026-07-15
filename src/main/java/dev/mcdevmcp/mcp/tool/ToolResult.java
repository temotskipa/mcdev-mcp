package dev.mcdevmcp.mcp.tool;

import java.util.List;
import java.util.Objects;

public record ToolResult(List<ToolContent> content, boolean isError) {
    public ToolResult {
        content = List.copyOf(Objects.requireNonNull(content, "content"));
    }
    
    public static ToolResult text(String text) {
        return new ToolResult(List.of(ToolContent.text(text)), false);
    }
    
    public static ToolResult error(String text) {
        return new ToolResult(List.of(ToolContent.text(text)), true);
    }
}
