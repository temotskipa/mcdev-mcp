package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;

final class McRecordVideoTool {
    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("record_video");

    private McRecordVideoTool() {
    }

    static ToolBinding<RecordVideoArguments> binding(MediaToolSupport support) {
        var decoder = ArgumentDecoder.sdk(RecordVideoWireArguments.class).map(RecordVideoArguments::from);
        return ToolBinding.compatibility(decoder, (arguments, _) -> support.recordVideo(arguments));
    }
}
