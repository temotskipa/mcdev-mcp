package dev.mcdevmcp.mcp;

import com.google.gson.JsonObject;
import dev.mcdevmcp.support.Cancellation;

@FunctionalInterface
public interface BlockingToolHandler {
    ToolResult handle(JsonObject arguments, Cancellation cancellation) throws Exception;
}
