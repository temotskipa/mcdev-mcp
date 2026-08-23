package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;

final class McScreenshotTool {
    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("screenshot");

    private McScreenshotTool() {
    }

    static ToolBinding<ScreenshotArguments> binding(MediaToolSupport support) {
        var decoder = ArgumentDecoder.sdk(ScreenshotWireArguments.class).map(ScreenshotArguments::from);
        return new ToolBinding<>(decoder, (arguments, _) -> support.screenshot(arguments));
    }
}
