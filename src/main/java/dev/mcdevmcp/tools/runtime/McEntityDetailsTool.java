package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.ToolBinding;

final class McEntityDetailsTool {
    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("entityDetails");

    private McEntityDetailsTool() {
    }

    static ToolBinding<EntityDetailsArguments> binding(RuntimeToolSupport support) {
        var decoder = ArgumentDecoder.sdk(EntityDetailsWireArguments.class).map(EntityDetailsArguments::from);
        return new ToolBinding<>(decoder, (arguments, _) -> support.container(ENDPOINT, RuntimeToolSupport.payload("entityId", arguments.entityId())));
    }
}