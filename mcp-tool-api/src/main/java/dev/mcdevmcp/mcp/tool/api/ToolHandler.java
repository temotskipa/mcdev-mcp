package dev.mcdevmcp.mcp.tool.api;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ToolHandler<A> {
    CompletionStage<ToolResult> handle(A arguments, ToolCancellation cancellation);
}
