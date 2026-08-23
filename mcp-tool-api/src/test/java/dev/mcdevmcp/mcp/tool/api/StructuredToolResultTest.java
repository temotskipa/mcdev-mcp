package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class StructuredToolResultTest {
    @Test
    void preservesTheTypedJavaValueUntilTheJsonBoundary() throws IOException {
        var value = new InventorySummary(2, List.of(new InventoryItem("minecraft:diamond", 3)));

        StructuredToolResult<InventorySummary> result = ToolResult.structured(InventorySummary.class, value, "2 inventory slots");

        assertSame(value, result.structuredContent());
        assertEquals(InventorySummary.class, result.structuredType().javaType());
        assertEquals("2 inventory slots", result.content().getFirst().text());
        String json = McpJsonDefaults.getMapper().writeValueAsString(result.structuredContent());
        assertEquals(Map.of("slots", 2, "items", List.of(Map.of("id", "minecraft:diamond", "count", 3))), McpJsonDefaults.getMapper().readValue(json, new TypeRef<Map<String, Object>>() {
        }));
    }
}
