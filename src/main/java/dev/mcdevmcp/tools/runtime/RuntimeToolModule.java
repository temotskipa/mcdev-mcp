package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeSession;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RuntimeToolModule {
    private RuntimeToolModule() {
    }

    public static List<ToolDeclaration<?>> declarations() {
        var declarations = new java.util.ArrayList<ToolDeclaration<?>>();
        for (RuntimeBindingDeclaration<?> binding : bindingDeclarations()) {
            addDeclaration(declarations, binding);
        }
        return List.copyOf(declarations);
    }

    private static <A> void addDeclaration(List<ToolDeclaration<?>> declarations, RuntimeBindingDeclaration<A> binding) {
        declarations.add(binding.declaration());
    }

    public static List<RuntimeBindingDeclaration<?>> bindingDeclarations() {
        return List.of(new RuntimeBindingDeclaration<>(McConnectTool.DECLARATION, context -> McConnectTool.binding(context.runtime())), new RuntimeBindingDeclaration<>(McExecuteTool.DECLARATION, context -> McExecuteTool.binding(context.runtime(), context.scriptLogger(), context.scriptLogger() != null)), new RuntimeBindingDeclaration<>(McSnapshotTool.DECLARATION, context -> McSnapshotTool.binding(context.runtime())), new RuntimeBindingDeclaration<>(McNearbyEntitiesTool.DECLARATION, context -> McNearbyEntitiesTool.binding(context.runtime())), new RuntimeBindingDeclaration<>(McEntityDetailsTool.DECLARATION, context -> McEntityDetailsTool.binding(context.runtime())), new RuntimeBindingDeclaration<>(McNearbyBlocksTool.DECLARATION, context -> McNearbyBlocksTool.binding(context.runtime())), new RuntimeBindingDeclaration<>(McBlockDetailsTool.DECLARATION, context -> McBlockDetailsTool.binding(context.runtime())), new RuntimeBindingDeclaration<>(McLookedAtEntityTool.DECLARATION, context -> McLookedAtEntityTool.binding(context.runtime())), new RuntimeBindingDeclaration<>(McChatHistoryTool.DECLARATION, context -> McChatHistoryTool.binding(context.runtime())), new RuntimeBindingDeclaration<>(McScreenInspectTool.DECLARATION, context -> McScreenInspectTool.binding(context.runtime())), new RuntimeBindingDeclaration<>(McScreenshotTool.DECLARATION, context -> McScreenshotTool.binding(context.media())), new RuntimeBindingDeclaration<>(McRecordVideoTool.DECLARATION, context -> McRecordVideoTool.binding(context.media())), new RuntimeBindingDeclaration<>(McGetItemTextureTool.DECLARATION, context -> McGetItemTextureTool.binding(context.media())), new RuntimeBindingDeclaration<>(McGetEntityItemTextureTool.DECLARATION, context -> McGetEntityItemTextureTool.binding(context.media())), new RuntimeBindingDeclaration<>(McGetItemTextureByIdTool.DECLARATION, context -> McGetItemTextureByIdTool.binding(context.media())), new RuntimeBindingDeclaration<>(McSetEntityGlowTool.DECLARATION, context -> McSetEntityGlowTool.binding(context.media())), new RuntimeBindingDeclaration<>(McSetBlockGlowTool.DECLARATION, context -> McSetBlockGlowTool.binding(context.media())), new RuntimeBindingDeclaration<>(McClearBlockGlowTool.DECLARATION, context -> McClearBlockGlowTool.binding(context.media())), new RuntimeBindingDeclaration<>(McJoinServerTool.DECLARATION, context -> McJoinServerTool.binding(context.runtime(), context.sessionControl())), new RuntimeBindingDeclaration<>(McLeaveServerTool.DECLARATION, context -> McLeaveServerTool.binding(context.runtime(), context.sessionControl())), new RuntimeBindingDeclaration<>(McWaitUntilInWorldTool.DECLARATION, context -> McWaitUntilInWorldTool.binding(context.sessionControl())), new RuntimeBindingDeclaration<>(McQuitClientTool.DECLARATION, context -> McQuitClientTool.binding(context.sessionControl())), new RuntimeBindingDeclaration<>(McWaitForBridgeTool.DECLARATION, context -> McWaitForBridgeTool.binding(context.sessionControl())), new RuntimeBindingDeclaration<>(McScriptLogsTool.DECLARATION, context -> McScriptLogsTool.binding(context.scriptLogger())), new RuntimeBindingDeclaration<>(McRunCommandTool.DECLARATION, context -> McRunCommandTool.binding(context.runtime(), context.sessionControl())));
    }

    public static RuntimeContext context(BridgeSession session, McpJsonMapper mapper, AppEnvironment environment) {
        return new RuntimeContext(session, mapper, environment);
    }

    public static Map<String, ToolBinding<?>> handlers(BridgeSession session, McpJsonMapper mapper) {
        return handlers(session, mapper, new AppEnvironment(Map.of()));
    }

    public static Map<String, ToolBinding<?>> handlers(BridgeSession session, McpJsonMapper mapper, AppEnvironment environment) {
        RuntimeContext context = context(session, mapper, environment);
        var handlers = new LinkedHashMap<String, ToolBinding<?>>();
        for (RuntimeBindingDeclaration<?> declaration : bindingDeclarations()) {
            add(handlers, declaration.name(), declaration.create(context));
        }
        return handlers;
    }

    private static <A> void add(Map<String, ToolBinding<?>> handlers, String name, ToolBinding<A> binding) {
        if (handlers.putIfAbsent(name, binding) != null) {
            throw new IllegalStateException("Duplicate runtime tool binding: " + name);
        }
    }
}
