package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.ToolBinding;

final class McSetBlockGlowTool {
    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("setBlockGlow");

    private McSetBlockGlowTool() {
    }

    static ToolBinding<BlockGlowArguments> binding(MediaToolSupport support) {
        var decoder = ArgumentDecoder.sdk(BlockGlowWireArguments.class).map(BlockGlowArguments::from);
        return new ToolBinding<>(decoder, (arguments, _) -> support.acknowledgement(ENDPOINT, RuntimeToolSupport.payload("x", arguments.x(), "y", arguments.y(), "z", arguments.z(), "glow", arguments.glow())));
    }
}
