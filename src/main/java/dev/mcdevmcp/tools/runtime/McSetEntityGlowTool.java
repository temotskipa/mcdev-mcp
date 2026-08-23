package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;

final class McSetEntityGlowTool {
    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("setEntityGlow");

    private McSetEntityGlowTool() {
    }

    static ToolBinding<EntityGlowArguments> binding(MediaToolSupport support) {
        var decoder = ArgumentDecoder.sdk(EntityGlowWireArguments.class).map(EntityGlowArguments::from);
        return new ToolBinding<>(decoder, (arguments, _) -> support.acknowledgement(ENDPOINT, RuntimeToolSupport.payload("entityId", arguments.entityId(), "glow", arguments.glow())));
    }
}
