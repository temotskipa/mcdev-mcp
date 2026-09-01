package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;

final class McNearbyBlocksTool {
    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("nearbyBlocks");

    private McNearbyBlocksTool() {
    }

    static ToolBinding<NearbyBlocksArguments> binding(RuntimeToolSupport support) {
        var decoder = ArgumentDecoder.sdk(NearbyBlocksWireArguments.class).map(NearbyBlocksArguments::from);
        return ToolBinding.compatibility(decoder, (arguments, _) -> support.container(ENDPOINT, RuntimeToolSupport.payload("range", arguments.range(), "limit", arguments.limit())));
    }
}
