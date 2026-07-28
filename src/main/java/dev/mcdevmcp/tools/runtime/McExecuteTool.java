package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.ToolBinding;

final class McExecuteTool {
    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("execute");

    private McExecuteTool() {
    }

    static ToolBinding<ExecuteArguments> binding(RuntimeToolSupport support, ScriptLogger scriptLogger, boolean scriptLogsEnabled) {
        var decoder = ArgumentDecoder.sdk(ExecuteWireArguments.class).map(ExecuteArguments::from);
        return new ToolBinding<>(decoder, (arguments, _) -> support.execute(arguments, scriptLogger, scriptLogsEnabled));
    }
}