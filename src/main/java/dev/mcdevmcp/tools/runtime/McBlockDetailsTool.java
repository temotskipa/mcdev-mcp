package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.ToolBinding;

final class McBlockDetailsTool {
    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("blockDetails");

    private McBlockDetailsTool() {
    }

    static ToolBinding<BlockDetailsArguments> binding(RuntimeToolSupport support) {
        var decoder = ArgumentDecoder.sdk(BlockDetailsWireArguments.class).map(BlockDetailsArguments::from);
        return new ToolBinding<>(decoder, (arguments, _) -> support.container(ENDPOINT, RuntimeToolSupport.payload("x", arguments.x(), "y", arguments.y(), "z", arguments.z())));
    }
}
