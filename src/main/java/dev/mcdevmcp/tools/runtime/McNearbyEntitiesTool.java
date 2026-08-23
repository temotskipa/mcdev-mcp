package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;

final class McNearbyEntitiesTool {
    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("nearbyEntities");

    private McNearbyEntitiesTool() {
    }

    static ToolBinding<NearbyEntitiesArguments> binding(RuntimeToolSupport support) {
        var decoder = ArgumentDecoder.sdk(NearbyEntitiesWireArguments.class).map(NearbyEntitiesArguments::from);
        return new ToolBinding<>(decoder, (arguments, _) -> support.container(ENDPOINT, RuntimeToolSupport.payload("range", arguments.range(), "limit", arguments.limit(), "includeIcons", arguments.includeIcons())));
    }
}