package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.support.Cancellation;

@FunctionalInterface
public interface BlockingToolHandler<A> {
    ToolResult handle(A arguments, Cancellation cancellation) throws Exception;
}
