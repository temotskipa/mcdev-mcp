package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolHandlers;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolCancellation;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class McJoinServerTool {
    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("joinServer");
    private static final BridgeEndpoint SNAPSHOT = new BridgeEndpoint("snapshot");

    private McJoinServerTool() {
    }

    static ToolBinding<JoinServerArguments> binding(RuntimeToolSupport runtime, SessionControlSupport sessionControl) {
        var decoder = ArgumentDecoder.sdk(JoinServerWireArguments.class).map(JoinServerArguments::from);
        return ToolBinding.compatibility(decoder, (arguments, cancellation) -> SessionControlSupport.recoverTool(SessionControlSupport.composeCancellable(sessionControl.checkSessionControlEnabled(), disabled -> {
            if (disabled != null) {
                return ToolHandlers.completed(ToolResult.error(disabled));
            }
            CompletionStage<Boolean> absenceGate = arguments.waitForWorld() ? preJoinAbsenceGate(sessionControl) : CompletableFuture.completedFuture(false);
            return SessionControlSupport.composeCancellable(absenceGate, requireAbsenceFirst -> sendJoin(runtime, sessionControl, arguments, cancellation, requireAbsenceFirst));
        })));
    }

    private static CompletionStage<Boolean> preJoinAbsenceGate(SessionControlSupport sessionControl) {
        return SessionControlSupport.handleCancellable(sessionControl.send(SNAPSHOT, RuntimeToolSupport.EMPTY_PAYLOAD, null), (response, failure) -> failure == null && response.success() && SessionControlSupport.classifyInWorldPoll(response.result(), null) instanceof InWorldPollResult.Joined);
    }

    private static CompletionStage<ToolResult> sendJoin(RuntimeToolSupport runtime, SessionControlSupport sessionControl, JoinServerArguments arguments, ToolCancellation cancellation, boolean requireAbsenceFirst) {
        return SessionControlSupport.composeCancellable(sessionControl.send(ENDPOINT, RuntimeToolSupport.payload("address", arguments.address(), "acceptResourcePacks", arguments.acceptResourcePacks()), Duration.ofSeconds(65)), response -> {
            ToolResult failure = RuntimeToolSupport.declaredFailure(response);
            if (failure != null) {
                return ToolHandlers.completed(failure);
            }
            if (!arguments.waitForWorld()) {
                return ToolHandlers.completed(ToolResult.text("Join accepted (connect started): " + McLeaveServerTool.safeResult(runtime, response) + "\nUse mc_wait_until_in_world to confirm the outcome."));
            }
            return SessionControlSupport.mapCancellable(sessionControl.waitUntilInWorld(arguments.timeoutSeconds(), requireAbsenceFirst, cancellation), outcome -> renderOutcome(arguments.address(), outcome));
        });
    }

    private static ToolResult renderOutcome(String address, InWorldWaitResult outcome) {
        String seconds = RuntimeToolSupport.nodeNumber(outcome.elapsedSeconds());
        return switch (outcome.state()) {
            case JOINED -> ToolResult.text("Joined " + address + " — in-world after " + seconds + "s.");
            case FAILED ->
                    ToolResult.error("Join failed: disconnected from " + address + ".\nReason: " + outcome.reason());
            case TIMEOUT ->
                    ToolResult.error("Still not in-world after " + seconds + "s joining " + address + " (no DisconnectedScreen either — possibly a slow login or resource pack download). Use mc_wait_until_in_world to keep waiting, or mc_screen_inspect to see the current screen.");
        };
    }
}
