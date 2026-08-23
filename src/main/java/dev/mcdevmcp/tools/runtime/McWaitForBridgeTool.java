package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.SessionInfo;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.ToolHandlers;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

final class McWaitForBridgeTool {
    private McWaitForBridgeTool() {
    }

    static ToolBinding<WaitForBridgeArguments> binding(SessionControlSupport support) {
        var decoder = ArgumentDecoder.sdk(WaitForBridgeWireArguments.class).map(WaitForBridgeArguments::from);
        return new ToolBinding<>(decoder, (arguments, cancellation) -> {
            List<String> notes = new CopyOnWriteArrayList<>();
            SessionControlSupport.ExpectedInstance expected = expectedInstance(support, arguments);
            CompletionStage<SessionControlSupport.FoundBridge> wait = support.waitForBridge(expected, arguments.timeoutSeconds(), notes, cancellation);
            CompletionStage<WaitAttempt> attempted = SessionControlSupport.handleCancellable(wait, WaitAttempt::new);
            return SessionControlSupport.composeCancellable(attempted, attempt -> {
                if (attempt.failure() != null) {
                    return ToolHandlers.completed(ToolResult.error(errorWithNotes(attempt.failure(), notes)));
                }
                return SessionControlSupport.handleCancellable(support.adoptPort(attempt.found().port()), (info, failure) -> failure == null ? renderSuccess(info, attempt.found().port(), notes) : ToolResult.error(errorWithNotes(failure, notes)));
            });
        });
    }

    private static SessionControlSupport.ExpectedInstance expectedInstance(SessionControlSupport support, WaitForBridgeArguments arguments) {
        if (arguments.expectedVersion().isPresent()) {
            return new SessionControlSupport.ExpectedInstance(arguments.expectedVersion(), Optional.empty());
        }
        return support.sessionInfo().map(info -> new SessionControlSupport.ExpectedInstance(Optional.of(info.version()), info.gameDir())).orElseGet(SessionControlSupport.ExpectedInstance::none);
    }

    private static ToolResult renderSuccess(SessionInfo info, int port, List<String> notes) {
        List<String> noteSnapshot = List.copyOf(notes);
        List<String> lines = new ArrayList<>();
        lines.add("Connected: Minecraft " + info.version().value() + " on port " + port + ".");
        info.gameDir().ifPresent(path -> lines.add("Game dir: " + path));
        info.sessionControlEnabled().ifPresent(enabled -> lines.add("Session control: " + (enabled ? "enabled" : "disabled")));
        noteSnapshot.forEach(note -> lines.add("Note: " + note));
        return ToolResult.text(String.join("\n", lines));
    }

    private static String errorWithNotes(Throwable failure, List<String> notes) {
        List<String> noteSnapshot = List.copyOf(notes);
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage() == null ? current.toString() : current.getMessage();
        return noteSnapshot.isEmpty() ? message : message + "\n" + String.join("\n", noteSnapshot.stream().map(note -> "Note: " + note).toList());
    }

    private record WaitAttempt(SessionControlSupport.FoundBridge found, Throwable failure) {
    }
}
