package dev.mcdevmcp.mcp;

import com.google.gson.JsonObject;
import dev.mcdevmcp.support.Cancellation;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ToolHandler {
    CompletionStage<ToolResult> handle(JsonObject arguments, Cancellation cancellation);
}