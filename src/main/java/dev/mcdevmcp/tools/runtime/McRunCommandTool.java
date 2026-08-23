package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.api.ToolResult;

final class McRunCommandTool {
    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("runCommand");

    private McRunCommandTool() {
    }

    static ToolBinding<RunCommandArguments> binding(RuntimeToolSupport runtime, SessionControlSupport sessionControl) {
        var decoder = ArgumentDecoder.sdk(RunCommandWireArguments.class).map(RunCommandArguments::from);
        return new ToolBinding<>(decoder, (arguments, _) -> SessionControlSupport.recoverTool(SessionControlSupport.mapCancellable(sessionControl.send(ENDPOINT, RuntimeToolSupport.payload("command", stripSlash(arguments.command())), null), response -> {
            ToolResult failure = RuntimeToolSupport.declaredFailure(response);
            if (failure != null) {
                return failure;
            }
            return ToolResult.text(runtime.prettyJson(RuntimeToolSupport.requireResult(ENDPOINT, response)));
        })));
    }

    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }
}
