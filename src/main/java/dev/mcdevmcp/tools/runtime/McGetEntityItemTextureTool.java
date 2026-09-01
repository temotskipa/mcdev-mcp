package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;

final class McGetEntityItemTextureTool {
    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("getEntityItemTexture");

    private McGetEntityItemTextureTool() {
    }

    static ToolBinding<EntityItemTextureArguments> binding(MediaToolSupport support) {
        var decoder = ArgumentDecoder.sdk(EntityItemTextureWireArguments.class).map(EntityItemTextureArguments::from);
        return ToolBinding.compatibility(decoder, (arguments, _) -> support.entityItemTexture(arguments));
    }
}
