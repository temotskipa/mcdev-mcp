package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
