package dev.mcdevmcp.mcp;

import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ToolCatalogContractTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {
    };
    
    static Map<String, Object> readContract(String name) throws IOException {
        try (var input = ToolCatalogContractTest.class.getResourceAsStream("/contracts/mcp/" + name)) {
            if (input == null) {
                throw new IOException("Missing contract: " + name);
            }
            return MAPPER.readValue(input.readAllBytes(), MAP_TYPE);
        }
    }
    
    static String normalize(Object value) throws IOException {
        return MAPPER.writeValueAsString(normalizeValue(value));
    }
    
    private static Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var normalized = new TreeMap<String, Object>();
            map.forEach((key, item) -> normalized.put((String) key, normalizeValue(item)));
            return normalized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ToolCatalogContractTest::normalizeValue).toList();
        }
        return value;
    }
    
    private static List<Map<String, Object>> contractTools(String name) throws IOException {
        return MAPPER.convertValue(readContract(name).get("result"), new TypeRef<Map<String, List<Map<String, Object>>>>() {
        }).get("tools");
    }
    
    private static List<Map<String, Object>> toToolList(List<ToolDefinition> definitions) {
        return definitions.stream().map(definition -> Map.of("name", definition.name(), "description", definition.description(), "inputSchema", definition.inputSchema())).toList();
    }
    
    private static ToolMetadata[] metadata(String... names) {
        var metadata = new ArrayList<ToolMetadata>();
        for (String name : names) {
            metadata.add(new ToolMetadata(name, "description", Map.of("type", "object", "properties", Map.of())));
        }
        return metadata.toArray(ToolMetadata[]::new);
    }
    
    private static ToolBinding<TestEmptyArguments> binding() {
        return new ToolBinding<>(ArgumentDecoder.sdk(TestEmptyArguments.class), (_, _) -> ToolHandlers.completed(ToolResult.text("ok")));
    }
    
    @Test
    void defaultToolListMatchesTheNodeContract() throws Exception {
        var catalog = ToolCatalog.load(new AppEnvironment(Map.of()), Map.of(), MAPPER);
        
        assertEquals(normalize(contractTools("tools-list-default.json")), normalize(toToolList(catalog.enabledDefinitions())));
        assertFalse(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_script_logs")));
        assertFalse(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_run_command")));
    }
    
    @Test
    void devEnabledToolListMatchesTheNodeContractInExactMetadataOrder() throws Exception {
        var catalog = ToolCatalog.load(new AppEnvironment(Map.of("MCDEV_SCRIPT_LOGS", "TRUE", "MCDEV_RUN_COMMAND", "1")), Map.of(), MAPPER);
        
        assertEquals(normalize(contractTools("tools-list-dev.json")), normalize(toToolList(catalog.enabledDefinitions())));
        assertTrue(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_record_video")));
    }
    
    @Test
    void availabilityGatesAcceptOnlyOneOrTrueWithoutTrimming() {
        var catalog = ToolCatalog.load(new AppEnvironment(Map.of("MCDEV_SCRIPT_LOGS", " true ", "MCDEV_RUN_COMMAND", "TRUE")), Map.of(), MAPPER);
        
        assertFalse(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_script_logs")));
        assertTrue(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_run_command")));
    }
    
    @Test
    void unknownToolReturnsTheNodeCompatibilityError() {
        var result = ToolCatalog.load(new AppEnvironment(Map.of()), Map.of(), MAPPER).dispatch("not_a_tool", Map.of(), Cancellation.none()).toCompletableFuture().resultNow();
        
        assertTrue(result.isError());
        assertEquals("Unknown tool: not_a_tool", result.content().getFirst().text());
    }
    
    @Test
    void unboundMigrationToolReturnsTheStagedUnavailableError() {
        var result = ToolCatalog.load(new AppEnvironment(Map.of()), Map.of(), MAPPER).dispatch("mc_version", Map.of(), Cancellation.none()).toCompletableFuture().resultNow();
        
        assertTrue(result.isError());
        assertEquals("Tool handler is not available in this migration build: mc_version", result.content().getFirst().text());
    }
    
    @Test
    void startupRejectsDuplicateMetadataHandlersAndMalformedSchemas() {
        List<Map.Entry<String, ToolBinding<?>>> duplicateHandlers = List.of(new AbstractMap.SimpleImmutableEntry<>("mc_version", binding()), new AbstractMap.SimpleImmutableEntry<>("mc_version", binding()));
        
        assertEquals("Duplicate tool metadata: mc_version", assertThrows(IllegalArgumentException.class, () -> ToolCatalog.fromMetadata(new AppEnvironment(Map.of()), MAPPER, metadata("mc_version", "mc_version"), List.of())).getMessage());
        assertEquals("Duplicate tool handler: mc_version", assertThrows(IllegalArgumentException.class, () -> ToolCatalog.fromMetadata(new AppEnvironment(Map.of()), MAPPER, metadata("mc_version"), duplicateHandlers)).getMessage());
        assertEquals("Handler without tool metadata: missing", assertThrows(IllegalArgumentException.class, () -> ToolCatalog.fromMetadata(new AppEnvironment(Map.of()), MAPPER, metadata("mc_version"), Map.<String, ToolBinding<?>>of("missing", binding()).entrySet())).getMessage());
        
        ToolMetadata[] malformed = metadata("mc_version");
        malformed[0] = new ToolMetadata("mc_version", "description", Map.of("type", "array"));
        assertEquals("Malformed input schema for tool: mc_version", assertThrows(IllegalArgumentException.class, () -> ToolCatalog.fromMetadata(new AppEnvironment(Map.of()), MAPPER, malformed, List.of())).getMessage());
    }
    
    @Test
    void synchronousHandlerFailureUsesTheNodeErrorEnvelope() {
        var synchronous = new ToolBinding<>(ArgumentDecoder.sdk(TestEmptyArguments.class), (TestEmptyArguments _, Cancellation _) -> {
            throw new IllegalStateException("sync failure");
        });
        var syncCatalog = ToolCatalog.fromMetadata(new AppEnvironment(Map.of()), MAPPER, metadata("mc_version"), Map.<String, ToolBinding<?>>of("mc_version", synchronous).entrySet());
        
        assertEquals("Error executing mc_version: sync failure", syncCatalog.dispatch("mc_version", Map.of(), Cancellation.none()).toCompletableFuture().resultNow().content().getFirst().text());
    }
}
