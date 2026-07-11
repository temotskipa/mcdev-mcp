package dev.mcdevmcp.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceCatalogTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    private static String contractText(String name) throws Exception {
        var result = MAPPER.convertValue(ToolCatalogContractTest.readContract(name).get("result"), new TypeRef<Map<String, List<Map<String, Object>>>>() {
        });
        return (String) result.get("contents").getFirst().get("text");
    }

    @Test
    void resourceListMatchesTheNodeContract() throws Exception {
        var catalog = new ResourceCatalog();
        var actual = catalog.definitions().stream()
                .map(definition -> Map.<String, Object>of(
                        "uri", definition.uri().toString(),
                        "name", definition.name(),
                        "title", definition.title(),
                        "description", definition.description(),
                        "mimeType", definition.mimeType()))
                .toList();
        var expected = MAPPER.convertValue(ToolCatalogContractTest.readContract("resources-list.json").get("result"), new TypeRef<Map<String, List<Map<String, Object>>>>() {
        }).get("resources");

        assertEquals(ToolCatalogContractTest.normalize(expected), ToolCatalogContractTest.normalize(actual));
    }

    @Test
    void resourceContentsMatchTheNodeFixtures() throws Exception {
        var catalog = new ResourceCatalog();

        assertEquals(
                contractText("resource-python-scripting.json"),
                catalog.read(URI.create("mcdev://guides/python-scripting")).text());
        assertEquals(
                contractText("resource-dev-loop.json"),
                catalog.read(URI.create("mcdev://guides/dev-loop")).text());
    }

    @Test
    void unknownResourceUsesTheNodeErrorText() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceCatalog().read(URI.create("mcdev://guides/missing")));

        assertEquals("Unknown resource URI: mcdev://guides/missing", exception.getMessage());
    }
}
