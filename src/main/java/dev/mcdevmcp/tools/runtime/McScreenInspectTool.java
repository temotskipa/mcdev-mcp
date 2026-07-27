package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.ToolBinding;

final class McScreenInspectTool {
    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("screenInspect");

    private McScreenInspectTool() {
    }

    static ToolBinding<ScreenInspectArguments> binding(RuntimeToolSupport support) {
        var decoder = ArgumentDecoder.sdk(ScreenInspectWireArguments.class).map(ScreenInspectArguments::from);
        return new ToolBinding<>(decoder, (arguments, _) -> support.container(ENDPOINT, RuntimeToolSupport.payload("includeIcons", arguments.includeIcons())));
    }
}
