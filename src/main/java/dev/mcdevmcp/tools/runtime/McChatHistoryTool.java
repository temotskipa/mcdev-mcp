package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.ToolBinding;

final class McChatHistoryTool {
    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("chatHistory");

    private McChatHistoryTool() {
    }

    static ToolBinding<ChatHistoryArguments> binding(RuntimeToolSupport support) {
        var decoder = ArgumentDecoder.sdk(ChatHistoryWireArguments.class).map(ChatHistoryArguments::from);
        return new ToolBinding<>(decoder, (arguments, _) -> support.container(ENDPOINT, RuntimeToolSupport.payload("limit", arguments.limit(), "includeJson", arguments.includeJson())));
    }
}
