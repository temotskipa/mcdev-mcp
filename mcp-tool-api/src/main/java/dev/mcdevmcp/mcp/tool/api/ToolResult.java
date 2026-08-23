package dev.mcdevmcp.mcp.tool.api;

import java.util.List;

public sealed interface ToolResult permits ContentToolResult, StructuredToolResult {
    static ToolResult content(List<ToolContent> content, boolean isError) {
        return new ContentToolResult(content, isError);
    }

    static ToolResult text(String text) {
        return content(List.of(ToolContent.text(text)), false);
    }

    static ToolResult error(String text) {
        return content(List.of(ToolContent.text(text)), true);
    }

    static <T> StructuredToolResult<T> structured(JsonType<T> type, T value, String fallbackText) {
        return new StructuredToolResult<>(List.of(ToolContent.text(fallbackText)), type, value, false);
    }

    static <T> StructuredToolResult<T> structured(Class<T> type, T value, String fallbackText) {
        return structured(JsonType.of(type), value, fallbackText);
    }

    List<ToolContent> content();

    boolean isError();
}
