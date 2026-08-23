package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;

final class McGetItemTextureByIdTool {
    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("getItemTextureById");

    private McGetItemTextureByIdTool() {
    }

    static ToolBinding<ItemTextureByIdArguments> binding(MediaToolSupport support) {
        var decoder = ArgumentDecoder.sdk(ItemTextureByIdWireArguments.class).map(ItemTextureByIdArguments::from);
        return new ToolBinding<>(decoder, (arguments, _) -> support.itemTextureById(arguments));
    }
}
