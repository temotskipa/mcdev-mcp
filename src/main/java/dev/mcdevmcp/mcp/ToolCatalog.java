package dev.mcdevmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.JsonSupport;

import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

public final class ToolCatalog {
    private static final Map<String, ToolAvailability> AVAILABILITY = Map.of("mc_script_logs", ToolAvailability.SCRIPT_LOGS, "mc_run_command", ToolAvailability.RUN_COMMAND);
    
    private final AppEnvironment environment;
    private final List<ToolDefinition> definitions;
    private final Map<String, ToolDefinition> definitionsByName;
    
    private ToolCatalog(AppEnvironment environment, List<ToolDefinition> definitions) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.definitions = List.copyOf(definitions);
        var byName = new HashMap<String, ToolDefinition>();
        for (var definition : definitions) {
            if (byName.putIfAbsent(definition.name(), definition) != null) {
                throw new IllegalArgumentException("Duplicate tool metadata: " + definition.name());
            }
        }
        definitionsByName = Map.copyOf(byName);
    }
    
    public static ToolCatalog load(AppEnvironment environment, Map<String, ToolHandler> handlers) {
        return fromMetadata(environment, JsonSupport.readArrayResource("/mcp/tools.json"), Objects.requireNonNull(handlers, "handlers").entrySet());
    }
    
    static ToolCatalog fromMetadata(AppEnvironment environment, JsonArray metadata, Iterable<Map.Entry<String, ToolHandler>> handlers) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(handlers, "handlers");
        
        Map<String, ToolHandler> boundHandlers = collectHandlers(handlers);
        Set<String> metadataNames = new HashSet<>();
        var definitions = new ArrayList<ToolDefinition>();
        for (JsonElement element : metadata) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Malformed tool metadata entry");
            }
            JsonObject tool = element.getAsJsonObject();
            String name = requiredString(tool, "name");
            if (!metadataNames.add(name)) {
                throw new IllegalArgumentException("Duplicate tool metadata: " + name);
            }
            String description = requiredString(tool, "description");
            if (!tool.has("inputSchema") || !tool.get("inputSchema").isJsonObject()) {
                throw new IllegalArgumentException("Malformed input schema for tool: " + name);
            }
            JsonObject inputSchema = validatedSchema(name, tool.getAsJsonObject("inputSchema"));
            ToolHandler handler = boundHandlers.get(name);
            if (handler == null) {
                handler = unavailable(name);
            }
            definitions.add(new ToolDefinition(name, description, inputSchema, handler, AVAILABILITY.getOrDefault(name, ToolAvailability.ALWAYS)));
        }
        for (String name : boundHandlers.keySet()) {
            if (!metadataNames.contains(name)) {
                throw new IllegalArgumentException("Handler without tool metadata: " + name);
            }
        }
        return new ToolCatalog(environment, definitions);
    }
    
    private static Map<String, ToolHandler> collectHandlers(Iterable<Map.Entry<String, ToolHandler>> handlers) {
        Map<String, ToolHandler> collected = new LinkedHashMap<>();
        for (Map.Entry<String, ToolHandler> entry : handlers) {
            String name = Objects.requireNonNull(entry.getKey(), "Tool handler name");
            ToolHandler handler = Objects.requireNonNull(entry.getValue(), "Tool handler: " + name);
            if (name.isBlank()) {
                throw new IllegalArgumentException("Tool handler name must not be blank");
            }
            if (collected.putIfAbsent(name, handler) != null) {
                throw new IllegalArgumentException("Duplicate tool handler: " + name);
            }
        }
        return Map.copyOf(collected);
    }
    
    private static JsonObject validatedSchema(String name, JsonObject schema) {
        if (!schema.has("type") || !schema.get("type").isJsonPrimitive() || !schema.getAsJsonPrimitive("type").isString() || !schema.get("type").getAsString().equals("object")) {
            throw new IllegalArgumentException("Malformed input schema for tool: " + name);
        }
        if (schema.has("properties") && !schema.get("properties").isJsonObject()) {
            throw new IllegalArgumentException("Malformed input schema properties for tool: " + name);
        }
        if (schema.has("required") && !schema.get("required").isJsonArray()) {
            throw new IllegalArgumentException("Malformed input schema required list for tool: " + name);
        }
        return schema.deepCopy();
    }
    
    public static String errorText(String name, Throwable exception) {
        Throwable current = Objects.requireNonNull(exception, "exception");
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return "Error executing " + name + ": " + (message == null ? current.toString() : message);
    }
    
    private static ToolHandler unavailable(String name) {
        return (_, _) -> ToolHandlers.completed(ToolResult.error("Tool handler is not available in this migration build: " + name));
    }
    
    private static String requiredString(JsonObject object, String property) {
        if (!object.has(property) || !object.get(property).isJsonPrimitive() || !object.getAsJsonPrimitive(property).isString()) {
            throw new IllegalArgumentException("Malformed tool metadata property: " + property);
        }
        String value = object.get(property).getAsString();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Blank tool metadata property: " + property);
        }
        return value;
    }
    
    public List<ToolDefinition> enabledDefinitions() {
        return definitions.stream().filter(this::isEnabled).toList();
    }
    
    public CompletionStage<ToolResult> dispatch(String name, JsonObject arguments, Cancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        ToolDefinition definition = definitionsByName.get(name);
        if (definition == null || !isEnabled(definition)) {
            return ToolHandlers.completed(ToolResult.error("Unknown tool: " + name));
        }
        try {
            CompletionStage<ToolResult> result = definition.handler().handle(arguments == null ? new JsonObject() : arguments.deepCopy(), cancellation);
            return Objects.requireNonNull(result, "Tool handler result: " + name);
        } catch (RuntimeException exception) {
            return ToolHandlers.completed(ToolResult.error(errorText(name, exception)));
        }
    }
    
    private boolean isEnabled(ToolDefinition definition) {
        return switch (definition.availability()) {
            case ALWAYS -> true;
            case SCRIPT_LOGS -> environment.isTruthy("MCDEV_SCRIPT_LOGS");
            case RUN_COMMAND -> environment.isTruthy("MCDEV_RUN_COMMAND");
        };
    }
}
