package dev.mcdevmcp.mcp.tool.api.smoke;

import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.api.StructuredToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.mcp.tool.api.TypedJson;
import io.modelcontextprotocol.json.McpJsonDefaults;

import java.util.Map;

public final class JpmsSmokeMain {
    private JpmsSmokeMain() {
    }

    static void main() throws Exception {
        var mapper = McpJsonDefaults.getMapper();
        var decoded = ArgumentDecoder.sdk(Payload.class).decode(mapper, Map.of("value", "jpms-ok"));
        if (!"jpms-ok".equals(decoded.value())) {
            throw new IllegalStateException("JPMS mapper round trip returned an unexpected value");
        }
        var genericDecoded = TypedJson.of(Map.of("value", "json-ok"), Payload.class).decode(mapper);
        if (!"json-ok".equals(genericDecoded.value())) {
            throw new IllegalStateException("JPMS generic JSON conversion returned an unexpected value");
        }
        StructuredToolResult<Payload> result = ToolResult.structured(Payload.class, decoded, "jpms-ok");
        if (!mapper.writeValueAsString(result.structuredContent()).contains("jpms-ok")) {
            throw new IllegalStateException("JPMS structured result did not serialize its typed value");
        }
        if (McpJsonDefaults.getSchemaValidator() == null) {
            throw new IllegalStateException("JPMS schema validator provider was not resolved");
        }
    }
}
