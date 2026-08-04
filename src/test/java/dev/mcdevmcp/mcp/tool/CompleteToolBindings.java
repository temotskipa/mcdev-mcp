package dev.mcdevmcp.mcp.tool;

import io.modelcontextprotocol.json.McpJsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CompleteToolBindings {
    private CompleteToolBindings() {
    }

    public static Map<String, ToolBinding<?>> including(McpJsonMapper mapper, Map<String, ToolBinding<?>> selectedBindings) {
        var bindings = new LinkedHashMap<String, ToolBinding<?>>();
        for (ToolMetadata metadata : ToolCatalog.loadMetadata(mapper)) {
            bindings.put(metadata.name(), new ToolBinding<>((_, arguments) -> Map.copyOf(arguments), (_, _) -> ToolHandlers.completed(ToolResult.error("Unexpected test handler invocation"))));
        }
        selectedBindings.forEach((name, binding) -> {
            if (!bindings.containsKey(name)) {
                throw new IllegalArgumentException("Handler without tool metadata: " + name);
            }
            bindings.put(name, binding);
        });
        return Map.copyOf(bindings);
    }
}
