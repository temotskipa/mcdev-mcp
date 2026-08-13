package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.JsonResourceReader;
import dev.mcdevmcp.support.JsonValues;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

public final class ToolCatalog {
    private static final Map<String, ToolAvailability> AVAILABILITY = Map.of("mc_script_logs", ToolAvailability.SCRIPT_LOGS, "mc_run_command", ToolAvailability.RUN_COMMAND);

    private final AppEnvironment environment;
    private final McpJsonMapper mapper;
    private final List<ToolDefinition> definitions;
    private final Map<String, ToolDefinition> definitionsByName;

    private ToolCatalog(AppEnvironment environment, McpJsonMapper mapper, List<ToolDefinition> definitions) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.definitions = List.copyOf(definitions);
        var byName = new HashMap<String, ToolDefinition>();
        for (var definition : definitions) {
            if (byName.putIfAbsent(definition.name(), definition) != null) {
                throw new IllegalArgumentException("Duplicate tool metadata: " + definition.name());
            }
        }
        definitionsByName = Map.copyOf(byName);
    }

    public static ToolCatalog load(AppEnvironment environment, Map<String, ToolBinding<?>> bindings, McpJsonMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return fromMetadata(environment, mapper, loadMetadata(mapper), Objects.requireNonNull(bindings, "bindings").entrySet());
    }

    public static ToolMetadata[] loadMetadata(McpJsonMapper mapper) {
        return new JsonResourceReader(Objects.requireNonNull(mapper, "mapper")).read("/mcp/tools.json", ToolMetadata[].class);
    }

    public static ToolCatalog load(AppEnvironment environment, Map<String, ToolBinding<?>> bindings, McpJsonMapper mapper, ExecutorService blockingExecutor) {
        Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        var adaptedBindings = new LinkedHashMap<String, ToolBinding<?>>();
        Objects.requireNonNull(bindings, "bindings").forEach((name, binding) -> adaptedBindings.put(name, binding.withBlockingExecutor(blockingExecutor)));
        return load(environment, adaptedBindings, mapper);
    }

    static ToolCatalog fromMetadata(AppEnvironment environment, McpJsonMapper mapper, ToolMetadata[] metadata, Iterable<Map.Entry<String, ToolBinding<?>>> bindings) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(bindings, "bindings");

        Set<String> metadataNames = new java.util.HashSet<>();
        for (ToolMetadata tool : metadata) {
            if (tool == null) {
                throw new IllegalArgumentException("Malformed tool metadata entry");
            }
            String name = tool.name();
            if (!metadataNames.add(name)) {
                throw new IllegalArgumentException("Duplicate tool metadata: " + name);
            }
            validatedSchema(name, tool.inputSchema());
        }

        Map<String, ToolBinding<?>> boundBindings = collectBindings(bindings);
        for (String name : boundBindings.keySet()) {
            if (!metadataNames.contains(name)) {
                throw new IllegalArgumentException("Handler without tool metadata: " + name);
            }
        }

        var definitions = new ArrayList<ToolDefinition>();
        for (ToolMetadata tool : metadata) {
            String name = tool.name();
            Map<String, Object> inputSchema = validatedSchema(name, tool.inputSchema());
            ToolBinding<?> binding = boundBindings.get(name);
            if (binding == null) {
                throw new IllegalArgumentException("Missing tool handler: " + name);
            }
            definitions.add(new ToolDefinition(name, tool.description(), inputSchema, binding, AVAILABILITY.getOrDefault(name, ToolAvailability.ALWAYS)));
        }
        return new ToolCatalog(environment, Objects.requireNonNull(mapper, "mapper"), definitions);
    }

    private static Map<String, ToolBinding<?>> collectBindings(Iterable<Map.Entry<String, ToolBinding<?>>> bindings) {
        Map<String, ToolBinding<?>> collected = new LinkedHashMap<>();
        for (Map.Entry<String, ToolBinding<?>> entry : bindings) {
            String name = Objects.requireNonNull(entry.getKey(), "Tool binding name");
            ToolBinding<?> binding = Objects.requireNonNull(entry.getValue(), "Tool binding: " + name);
            if (name.isBlank()) {
                throw new IllegalArgumentException("Tool binding name must not be blank");
            }
            if (collected.putIfAbsent(name, binding) != null) {
                throw new IllegalArgumentException("Duplicate tool handler: " + name);
            }
        }
        return Map.copyOf(collected);
    }

    private static Map<String, Object> validatedSchema(String name, Map<String, Object> schema) {
        if (!"object".equals(schema.get("type"))) {
            throw new IllegalArgumentException("Malformed input schema for tool: " + name);
        }
        if (schema.containsKey("properties") && !(schema.get("properties") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Malformed input schema properties for tool: " + name);
        }
        if (schema.containsKey("required") && !(schema.get("required") instanceof List<?>)) {
            throw new IllegalArgumentException("Malformed input schema required list for tool: " + name);
        }
        return JsonValues.freezeMap(schema);
    }

    public static String errorText(String name, Throwable exception) {
        Throwable current = Objects.requireNonNull(exception, "exception");
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return "Error executing " + name + ": " + (message == null ? current.toString() : message);
    }

    public List<ToolDefinition> enabledDefinitions() {
        return definitions.stream().filter(this::isEnabled).toList();
    }

    public CompletionStage<ToolResult> dispatch(String name, Map<String, Object> arguments, Cancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        ToolDefinition definition = definitionsByName.get(name);
        if (definition == null || !isEnabled(definition)) {
            return ToolHandlers.completed(ToolResult.error("Unknown tool: " + name));
        }
        try {
            CompletionStage<ToolResult> result = definition.binding().invoke(mapper, arguments == null ? Map.of() : JsonValues.freezeMap(arguments), cancellation);
            return Objects.requireNonNull(result, "Tool handler result: " + name);
        } catch (RuntimeException exception) {
            return ToolHandlers.completed(ToolResult.error(errorText(name, exception)));
        }
    }

    private boolean isEnabled(ToolDefinition definition) {
        return switch (definition.availability()) {
            case ALWAYS -> true;
            case SCRIPT_LOGS ->
                    environment.isTruthy("MCDEV_SCRIPT_LOGS") || environment.value("MCDEV_SESSION_LOG_DIR").filter(value -> !value.isBlank()).isPresent();
            case RUN_COMMAND -> environment.isTruthy("MCDEV_RUN_COMMAND");
        };
    }
}
