package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.mcp.McpContractTestSupport;
import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolCatalogContractTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    private static List<Map<String, Object>> contractTools(String name) throws Exception {
        return MAPPER.convertValue(McpContractTestSupport.readContract(name).get("result"), new TypeRef<Map<String, List<Map<String, Object>>>>() {
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

    private static Map<String, ToolBinding<?>> completeBindings() {
        var bindings = new java.util.LinkedHashMap<String, ToolBinding<?>>();
        for (ToolMetadata tool : ToolCatalog.loadMetadata(MAPPER)) {
            bindings.put(tool.name(), binding());
        }
        return Map.copyOf(bindings);
    }

    @Test
    void defaultToolListMatchesTheNodeContract() throws Exception {
        var catalog = ToolCatalog.load(new AppEnvironment(Map.of()), completeBindings(), MAPPER);

        assertEquals(McpContractTestSupport.normalize(contractTools("tools-list-default.json")), McpContractTestSupport.normalize(toToolList(catalog.enabledDefinitions())));
        assertFalse(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_script_logs")));
        assertFalse(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_run_command")));
    }

    @Test
    void devEnabledToolListMatchesTheNodeContractInExactMetadataOrder() throws Exception {
        var catalog = ToolCatalog.load(new AppEnvironment(Map.of("MCDEV_SESSION_LOG_DIR", "/tmp/mcdev/session-logs", "MCDEV_RUN_COMMAND", "1")), completeBindings(), MAPPER);

        assertEquals(McpContractTestSupport.normalize(contractTools("tools-list-dev.json")), McpContractTestSupport.normalize(toToolList(catalog.enabledDefinitions())));
        assertTrue(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_record_video")));
    }

    @Test
    void retainedScriptLogSwitchEnablesTheNodeContractTool() {
        var catalog = ToolCatalog.load(new AppEnvironment(Map.of("MCDEV_SCRIPT_LOGS", "1")), completeBindings(), MAPPER);

        assertTrue(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_script_logs")));
        assertFalse(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_run_command")));
    }

    @Test
    void availabilityGatesTreatBlankLogPathAndUntrimmedRunCommandCorrectly() {
        var catalog = ToolCatalog.load(new AppEnvironment(Map.of("MCDEV_SESSION_LOG_DIR", "   ", "MCDEV_RUN_COMMAND", "TRUE")), completeBindings(), MAPPER);

        assertFalse(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_script_logs")));
        assertTrue(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_run_command")));
    }

    @Test
    void unknownToolReturnsTheNodeCompatibilityError() {
        var result = ToolCatalog.load(new AppEnvironment(Map.of()), completeBindings(), MAPPER).dispatch("not_a_tool", Map.of(), Cancellation.none()).toCompletableFuture().resultNow();

        assertTrue(result.isError());
        assertEquals("Unknown tool: not_a_tool", result.content().getFirst().text());
    }

    @Test
    void startupRejectsMissingToolHandlers() {
        var exception = assertThrows(IllegalArgumentException.class, () -> ToolCatalog.fromMetadata(new AppEnvironment(Map.of()), MAPPER, metadata("mc_version"), List.of()));

        assertEquals("Missing tool handler: mc_version", exception.getMessage());
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
