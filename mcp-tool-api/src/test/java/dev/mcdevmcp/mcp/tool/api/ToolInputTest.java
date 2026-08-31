package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolInputTest {
    @Test
    void decodesTheCompleteArgumentMapIntoItsRecordType() {
        ToolInput<SchemaInput> input = ToolInput.of(SchemaInput.class, RecordInputSchemaFactory.standard());

        SchemaInput result = input.decode(
                McpJsonDefaults.getMapper(),
                Map.of(
                        "query", "nearby blocks",
                        "includeDetails", true,
                        "threshold", new BigDecimal("2.75"),
                        "mode", "FAST",
                        "limit", 9L,
                        "optionalFilter", "stone"
                )
        );

        assertEquals(new SchemaInput("nearby blocks", true, new BigDecimal("2.75"), InputMode.FAST, 9L, "stone"), result);
    }

    @Test
    void generatesScalarSchemasThatMatchDelegatingRecordAndEnumDecoding() {
        ToolInput<ScalarInput> input = ToolInput.of(ScalarInput.class, RecordInputSchemaFactory.standard());

        ScalarInput result = input.decode(McpJsonDefaults.getMapper(), Map.of("version", "1.21.1", "mode", "deep"));

        assertEquals(new ScalarInput(new WireVersion("1.21.1"), WireMode.THOROUGH), result);
        assertEquals(
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "version", Map.of("type", "string"),
                                "mode", Map.of("type", "string", "enum", java.util.List.of("quick", "deep"))
                        ),
                        "required", java.util.List.of("version", "mode")
                ),
                input.schema().value()
        );
    }

    @Test
    void exposesOnlyPrivateConstructors() {
        assertTrue(Arrays.stream(ToolInput.class.getDeclaredConstructors()).allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
    }
}
