package dev.mcdevmcp.mcp.tool.api;

@FunctionalInterface
public interface BlockingToolHandler<A> {
    @SuppressWarnings("unused")
    ToolResult handle(A arguments, ToolCancellation cancellation) throws Exception;
}
