package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.api.ToolResult;

final class McWaitUntilInWorldTool {
    private McWaitUntilInWorldTool() {
    }

    static ToolBinding<WaitUntilInWorldArguments> binding(SessionControlSupport support) {
        var decoder = ArgumentDecoder.sdk(WaitUntilInWorldWireArguments.class).map(WaitUntilInWorldArguments::from);
        return ToolBinding.compatibility(decoder, (arguments, cancellation) -> SessionControlSupport.recoverTool(SessionControlSupport.mapCancellable(support.waitUntilInWorld(arguments.timeoutSeconds(), arguments.requireAbsenceFirst(), cancellation), McWaitUntilInWorldTool::render)));
    }

    private static ToolResult render(InWorldWaitResult outcome) {
        String seconds = RuntimeToolSupport.nodeNumber(outcome.elapsedSeconds());
        return switch (outcome.state()) {
            case JOINED -> ToolResult.text("In-world after " + seconds + "s.");
            case FAILED -> ToolResult.error("Join failed — DisconnectedScreen shown.\nReason: " + outcome.reason());
            case TIMEOUT ->
                    ToolResult.error("Not in-world after " + seconds + "s and no DisconnectedScreen. Use mc_screen_inspect to see what screen the client is on.");
        };
    }
}
