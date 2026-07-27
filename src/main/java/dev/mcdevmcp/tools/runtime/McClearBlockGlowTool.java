package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.ToolBinding;

final class McClearBlockGlowTool {
    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("clearBlockGlow");

    private McClearBlockGlowTool() {
    }

    static ToolBinding<RuntimeEmptyArguments> binding(MediaToolSupport support) {
        return new ToolBinding<>(ArgumentDecoder.sdk(RuntimeEmptyArguments.class), (_, _) -> support.acknowledgement(ENDPOINT, RuntimeToolSupport.EMPTY_PAYLOAD));
    }
}
