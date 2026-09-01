package dev.mcdevmcp.mcp.tool.api;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonObjectSchemaTest {
    @Test
    void rejectsMutableValuesThatAreNotJsonTreeNodes() {
        Map<String, Object> setSchema = new LinkedHashMap<>();
        setSchema.put("type", "object");
        setSchema.put("enum", Set.of("unexpected"));

        Map<String, Object> arraySchema = new LinkedHashMap<>();
        arraySchema.put("type", "object");
        arraySchema.put("examples", new String[]{"unexpected"});

        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(setSchema));
        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(arraySchema));
    }
}
