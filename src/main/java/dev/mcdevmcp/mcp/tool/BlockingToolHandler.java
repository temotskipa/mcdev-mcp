package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.support.Cancellation;

@FunctionalInterface
public interface BlockingToolHandler<A> {
    @SuppressWarnings("unused")
    ToolResult handle(A arguments, Cancellation cancellation) throws Exception;
}
