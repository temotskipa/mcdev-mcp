package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;

final class McGetItemTextureTool {
    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("getItemTexture");

    private McGetItemTextureTool() {
    }

    static ToolBinding<ItemTextureArguments> binding(MediaToolSupport support) {
        var decoder = ArgumentDecoder.sdk(ItemTextureWireArguments.class).map(ItemTextureArguments::from);
        return new ToolBinding<>(decoder, (arguments, _) -> support.itemTexture(arguments));
    }
}
