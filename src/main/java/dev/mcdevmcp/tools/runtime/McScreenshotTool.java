package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;

final class McScreenshotTool {
    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("screenshot");

    private McScreenshotTool() {
    }

    static ToolBinding<ScreenshotArguments> binding(MediaToolSupport support) {
        var decoder = ArgumentDecoder.sdk(ScreenshotWireArguments.class).map(ScreenshotArguments::from);
        return ToolBinding.compatibility(decoder, (arguments, _) -> support.screenshot(arguments));
    }
}
