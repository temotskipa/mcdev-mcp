package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;

final class McSnapshotTool {
    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("snapshot");

    private McSnapshotTool() {
    }

    static ToolBinding<RuntimeEmptyArguments> binding(RuntimeToolSupport support) {
        return new ToolBinding<>(ArgumentDecoder.sdk(RuntimeEmptyArguments.class), (_, _) -> support.container(ENDPOINT, RuntimeToolSupport.EMPTY_PAYLOAD));
    }
}
