package dev.mcdevmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class ToolCatalogContractTest {
    private static JsonArray contractTools(String name) throws IOException {
        return contract(name).getAsJsonObject("result").getAsJsonArray("tools");
    }

    private static JsonObject contract(String name) throws IOException {
        try (var input = ToolCatalogContractTest.class.getResourceAsStream("/contracts/mcp/" + name)) {
            if (input == null) {
                throw new IOException("Missing contract: " + name);
            }
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static JsonArray toToolList(List<ToolDefinition> definitions) {
        var tools = new JsonArray();
        for (var definition : definitions) {
            var tool = new JsonObject();
            tool.addProperty("name", definition.name());
            tool.addProperty("description", definition.description());
            tool.add("inputSchema", definition.inputSchema());
            tools.add(tool);
        }
        return tools;
    }

    private static JsonArray metadata(String... names) {
        var metadata = new JsonArray();
        for (String name : names) {
            var tool = new JsonObject();
            tool.addProperty("name", name);
            tool.addProperty("description", "description");
            var schema = new JsonObject();
            schema.addProperty("type", "object");
            schema.add("properties", new JsonObject());
            tool.add("inputSchema", schema);
            metadata.add(tool);
        }
        return metadata;
    }

    static String normalize(JsonElement element) {
        if (element.isJsonArray()) {
            var normalized = new JsonArray();
            for (var item : element.getAsJsonArray()) {
                normalized.add(JsonParser.parseString(normalize(item)));
            }
            return normalized.toString();
        }
        if (element.isJsonObject()) {
            var ordered = new TreeMap<String, JsonElement>();
            element.getAsJsonObject().entrySet().forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
            var normalized = new JsonObject();
            ordered.forEach((key, value) -> normalized.add(key, JsonParser.parseString(normalize(value))));
            return normalized.toString();
        }
        return element.toString();
    }

    @Test
    void defaultToolListMatchesTheNodeContract() throws Exception {
        var catalog = ToolCatalog.load(new AppEnvironment(Map.of()), Map.of());

        assertEquals(normalize(contractTools("tools-list-default.json")), normalize(toToolList(catalog.enabledDefinitions())));
        assertFalse(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_script_logs")));
        assertFalse(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_run_command")));
    }

    @Test
    void devEnabledToolListMatchesTheNodeContractInExactMetadataOrder() throws Exception {
        var catalog = ToolCatalog.load(new AppEnvironment(Map.of("MCDEV_SCRIPT_LOGS", "TRUE", "MCDEV_RUN_COMMAND", "1")), Map.of());

        assertEquals(normalize(contractTools("tools-list-dev.json")), normalize(toToolList(catalog.enabledDefinitions())));
        assertTrue(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_record_video")));
    }

    @Test
    void availabilityGatesAcceptOnlyOneOrTrueWithoutTrimming() {
        var catalog = ToolCatalog.load(new AppEnvironment(Map.of("MCDEV_SCRIPT_LOGS", " true ", "MCDEV_RUN_COMMAND", "TRUE")), Map.of());

        assertFalse(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_script_logs")));
        assertTrue(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_run_command")));
    }

    @Test
    void unknownToolReturnsTheNodeCompatibilityError() {
        var result = ToolCatalog.load(new AppEnvironment(Map.of()), Map.of()).dispatch("not_a_tool", new JsonObject(), Cancellation.none()).toCompletableFuture().resultNow();

        assertTrue(result.isError());
        assertEquals("Unknown tool: not_a_tool", result.content().getFirst().text());
    }

    @Test
    void unboundMigrationToolReturnsTheStagedUnavailableError() {
        var result = ToolCatalog.load(new AppEnvironment(Map.of()), Map.of()).dispatch("mc_version", new JsonObject(), Cancellation.none()).toCompletableFuture().resultNow();

        assertTrue(result.isError());
        assertEquals("Tool handler is not available in this migration build: mc_version", result.content().getFirst().text());
    }

    @Test
    void startupRejectsDuplicateMetadataHandlersAndMalformedSchemas() {
        ToolHandler handler = (_, _) -> ToolHandlers.completed(ToolResult.text("ok"));
        List<Map.Entry<String, ToolHandler>> duplicateHandlers = List.of(
                new AbstractMap.SimpleImmutableEntry<>("mc_version", handler),
                new AbstractMap.SimpleImmutableEntry<>("mc_version", handler));

        assertEquals(
                "Duplicate tool metadata: mc_version",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> ToolCatalog.fromMetadata(
                                        new AppEnvironment(Map.of()),
                                        metadata("mc_version", "mc_version"),
                                        List.of()))
                        .getMessage());
        assertEquals(
                "Duplicate tool handler: mc_version",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> ToolCatalog.fromMetadata(
                                        new AppEnvironment(Map.of()),
                                        metadata("mc_version"),
                                        duplicateHandlers))
                        .getMessage());
        assertEquals(
                "Handler without tool metadata: missing",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> ToolCatalog.fromMetadata(
                                        new AppEnvironment(Map.of()),
                                        metadata("mc_version"),
                                        Map.of("missing", handler).entrySet()))
                        .getMessage());

        JsonArray malformed = metadata("mc_version");
        malformed.get(0).getAsJsonObject().getAsJsonObject("inputSchema").addProperty("type", "array");
        assertEquals(
                "Malformed input schema for tool: mc_version",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> ToolCatalog.fromMetadata(
                                        new AppEnvironment(Map.of()),
                                        malformed,
                                        List.of()))
                        .getMessage());
    }

    @Test
    void synchronousHandlerFailureUsesTheNodeErrorEnvelope() {
        ToolHandler synchronous = (_, _) -> {
            throw new IllegalStateException("sync failure");
        };

        var syncCatalog = ToolCatalog.fromMetadata(
                new AppEnvironment(Map.of()),
                metadata("mc_version"),
                Map.of("mc_version", synchronous).entrySet());

        assertEquals(
                "Error executing mc_version: sync failure",
                syncCatalog.dispatch("mc_version", new JsonObject(), Cancellation.none())
                        .toCompletableFuture()
                        .resultNow()
                        .content()
                        .getFirst()
                        .text());
    }
}
