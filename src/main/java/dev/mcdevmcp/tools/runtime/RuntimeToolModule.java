package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeSession;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RuntimeToolModule {
    private RuntimeToolModule() {
    }

    public static Map<String, ToolBinding<?>> handlers(BridgeSession session, McpJsonMapper mapper) {
        var support = new RuntimeToolSupport(session, mapper);
        var handlers = new LinkedHashMap<String, ToolBinding<?>>();
        add(handlers, "mc_connect", McConnectTool.binding(support));
        add(handlers, "mc_execute", McExecuteTool.binding(support));
        add(handlers, "mc_snapshot", McSnapshotTool.binding(support));
        add(handlers, "mc_nearby_entities", McNearbyEntitiesTool.binding(support));
        add(handlers, "mc_entity_details", McEntityDetailsTool.binding(support));
        add(handlers, "mc_nearby_blocks", McNearbyBlocksTool.binding(support));
        add(handlers, "mc_block_details", McBlockDetailsTool.binding(support));
        add(handlers, "mc_looked_at_entity", McLookedAtEntityTool.binding(support));
        add(handlers, "mc_chat_history", McChatHistoryTool.binding(support));
        add(handlers, "mc_screen_inspect", McScreenInspectTool.binding(support));
        MediaRuntimeToolModule.handlers(new MediaToolSupport(support)).forEach((name, binding) -> add(handlers, name, binding));
        return handlers;
    }

    private static void add(Map<String, ToolBinding<?>> handlers, String name, ToolBinding<?> binding) {
        if (handlers.putIfAbsent(name, binding) != null) {
            throw new IllegalStateException("Duplicate runtime tool binding: " + name);
        }
    }
}
