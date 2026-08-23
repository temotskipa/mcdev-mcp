package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.ToolHandlers;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.api.ToolResult;

import java.util.Objects;

final class McLeaveServerTool {
    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("disconnect");

    private McLeaveServerTool() {
    }

    static ToolBinding<RuntimeEmptyArguments> binding(RuntimeToolSupport runtime, SessionControlSupport sessionControl) {
        return new ToolBinding<>(ArgumentDecoder.sdk(RuntimeEmptyArguments.class), (_, _) -> SessionControlSupport.recoverTool(SessionControlSupport.composeCancellable(sessionControl.checkSessionControlEnabled(), disabled -> {
            if (disabled != null) {
                return ToolHandlers.completed(ToolResult.error(disabled));
            }
            return SessionControlSupport.mapCancellable(sessionControl.send(ENDPOINT, RuntimeToolSupport.EMPTY_PAYLOAD, null), response -> {
                ToolResult failure = RuntimeToolSupport.declaredFailure(response);
                return Objects.requireNonNullElseGet(failure, () -> ToolResult.text("Disconnect queued: " + safeResult(runtime, response)));
            });
        })));
    }

    static String safeResult(RuntimeToolSupport runtime, dev.mcdevmcp.bridge.BridgeResponse response) {
        return response.resultPresent() ? runtime.prettyJson(response.result()) : "undefined";
    }
}
