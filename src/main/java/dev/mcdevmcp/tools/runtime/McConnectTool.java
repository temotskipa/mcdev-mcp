package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;

final class McConnectTool {
    private McConnectTool() {
    }

    static ToolBinding<ConnectArguments> binding(RuntimeToolSupport support) {
        var decoder = ArgumentDecoder.sdk(ConnectWireArguments.class).map(ConnectArguments::from);
        return ToolBinding.compatibility(decoder, (arguments, _) -> support.connect(arguments));
    }
}
