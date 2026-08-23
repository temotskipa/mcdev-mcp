package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;

final class McLookedAtEntityTool {
    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("lookedAtEntity");

    private McLookedAtEntityTool() {
    }

    static ToolBinding<LookedAtEntityArguments> binding(RuntimeToolSupport support) {
        var decoder = ArgumentDecoder.sdk(LookedAtEntityWireArguments.class).map(LookedAtEntityArguments::from);
        return new ToolBinding<>(decoder, (arguments, _) -> support.lookedAtEntity(arguments));
    }
}