package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TypedJsonTest {
    @Test
    void carriesItsGenericJavaTargetAlongsideTheRawJsonValue() {
        var json = TypedJson.of(List.of(Map.of("id", "minecraft:diamond", "count", 3)), new TypeRef<List<InventoryItem>>() {
        });

        var result = json.decode(McpJsonDefaults.getMapper());

        assertEquals("java.util.List<dev.mcdevmcp.mcp.tool.api.InventoryItem>", json.targetType().javaType().getTypeName());
        assertEquals(List.of(new InventoryItem("minecraft:diamond", 3)), result);
    }
}
