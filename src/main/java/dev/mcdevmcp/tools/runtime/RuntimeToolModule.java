package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeSession;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class RuntimeToolModule {
    private RuntimeToolModule() {
    }

    public static Map<String, ToolBinding<?>> handlers(BridgeSession session, McpJsonMapper mapper) {
        return handlers(session, mapper, new AppEnvironment(Map.of()));
    }

    public static Map<String, ToolBinding<?>> handlers(BridgeSession session, McpJsonMapper mapper, AppEnvironment environment) {
        var support = new RuntimeToolSupport(session, mapper);
        Path dataDirectory = ScriptLogger.dataDirectory(System.getProperty("os.name"), environment, Path.of(System.getProperty("user.home")));
        var scriptLogger = new ScriptLogger(dataDirectory, mapper, System.err::println);
        var sessionControl = new SessionControlSupport(session, environment, SchedulerHolder.SCHEDULER);
        var handlers = new LinkedHashMap<String, ToolBinding<?>>();
        add(handlers, "mc_connect", McConnectTool.binding(support));
        add(handlers, "mc_execute", McExecuteTool.binding(support, scriptLogger, environment.isTruthy("MCDEV_SCRIPT_LOGS")));
        add(handlers, "mc_snapshot", McSnapshotTool.binding(support));
        add(handlers, "mc_nearby_entities", McNearbyEntitiesTool.binding(support));
        add(handlers, "mc_entity_details", McEntityDetailsTool.binding(support));
        add(handlers, "mc_nearby_blocks", McNearbyBlocksTool.binding(support));
        add(handlers, "mc_block_details", McBlockDetailsTool.binding(support));
        add(handlers, "mc_looked_at_entity", McLookedAtEntityTool.binding(support));
        add(handlers, "mc_chat_history", McChatHistoryTool.binding(support));
        add(handlers, "mc_screen_inspect", McScreenInspectTool.binding(support));
        MediaRuntimeToolModule.handlers(new MediaToolSupport(support)).forEach((name, binding) -> add(handlers, name, binding));
        add(handlers, "mc_join_server", McJoinServerTool.binding(support, sessionControl));
        add(handlers, "mc_leave_server", McLeaveServerTool.binding(support, sessionControl));
        add(handlers, "mc_wait_until_in_world", McWaitUntilInWorldTool.binding(sessionControl));
        add(handlers, "mc_quit_client", McQuitClientTool.binding(sessionControl));
        add(handlers, "mc_wait_for_bridge", McWaitForBridgeTool.binding(sessionControl));
        add(handlers, "mc_script_logs", McScriptLogsTool.binding(scriptLogger));
        add(handlers, "mc_run_command", McRunCommandTool.binding(support, sessionControl));
        return handlers;
    }

    private static void add(Map<String, ToolBinding<?>> handlers, String name, ToolBinding<?> binding) {
        if (handlers.putIfAbsent(name, binding) != null) {
            throw new IllegalStateException("Duplicate runtime tool binding: " + name);
        }
    }

    private static final class SchedulerHolder {
        private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform().daemon(true).name("mcdev-session-poll").unstarted(runnable));
    }
}
