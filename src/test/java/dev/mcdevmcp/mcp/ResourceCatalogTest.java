package dev.mcdevmcp.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
        var actual = catalog.definitions().stream().map(definition -> Map.<String, Object>of("uri", definition.uri().toString(), "name", definition.name(), "title", definition.title(), "description", definition.description(), "mimeType", definition.mimeType())).toList();
        var expected = MAPPER.convertValue(ToolCatalogContractTest.readContract("resources-list.json").get("result"), new TypeRef<Map<String, List<Map<String, Object>>>>() {
        }).get("resources");
        
        assertEquals(ToolCatalogContractTest.normalize(expected), ToolCatalogContractTest.normalize(actual));
    }
    
    @Test
    void resourceContentsMatchExpectedContracts() throws Exception {
        var catalog = new ResourceCatalog();
        
        assertEquals(contractText("resource-python-scripting.json"), catalog.read(URI.create("mcdev://guides/python-scripting")).text());
        assertEquals(contractText("resource-dev-loop.json"), catalog.read(URI.create("mcdev://guides/dev-loop")).text());
    }
    
    @Test
    void pythonGuidePinsRetiredLinksWhilePreservingTheNodeOracle() throws Exception {
        var currentGuide = new ResourceCatalog().read(URI.create("mcdev://guides/python-scripting")).text();
        var oracleLink = "https://github.com/use-ai-for-mc/mcdev-mcp/blob/" + "7b98bdb4a1d885d588cd141d8eb21e3c5c18b2b6/src/tools/runtime/session.ts";
        
        assertTrue(currentGuide.contains(oracleLink));
        assertFalse(currentGuide.contains("](../src/tools/runtime/"));
        
        try (var oracleResource = ResourceCatalogTest.class.getResourceAsStream("/oracle/python-scripting-node.md")) {
            assertNotNull(oracleResource, "The frozen Node-visible Python guide must be retained separately");
            var frozenOracle = new String(oracleResource.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(frozenOracle.contains("](../src/tools/runtime/session.ts)"));
            assertNotEquals(frozenOracle, currentGuide);
        }
    }
    
    @Test
    void unknownResourceUsesTheNodeErrorText() {
        var exception = assertThrows(IllegalArgumentException.class, () -> new ResourceCatalog().read(URI.create("mcdev://guides/missing")));
        
        assertEquals("Unknown resource URI: mcdev://guides/missing", exception.getMessage());
    }
}
