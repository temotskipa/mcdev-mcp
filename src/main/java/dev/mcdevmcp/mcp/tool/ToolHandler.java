package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.support.Cancellation;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ToolHandler<A> {
    CompletionStage<ToolResult> handle(A arguments, Cancellation cancellation);
}
