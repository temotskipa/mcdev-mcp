package dev.mcdevmcp.mcp.tool.api.smoke;

import dev.mcdevmcp.mcp.tool.api.RecordInputSchemaFactory;
import dev.mcdevmcp.mcp.tool.api.StructuredToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolCancellation;
import dev.mcdevmcp.mcp.tool.api.ToolHandlers;
import dev.mcdevmcp.mcp.tool.api.ToolInput;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.mcp.tool.api.TypedJson;
import io.modelcontextprotocol.json.McpJsonDefaults;

import java.util.Map;

public final class JpmsSmokeMain {
    private JpmsSmokeMain() {
    }

    static void main() throws Exception {
        var mapper = McpJsonDefaults.getMapper();
        var input = ToolInput.of(Payload.class, RecordInputSchemaFactory.standard());
        var decoded = input.decode(mapper, Map.of("value", "jpms-ok"));
        if (!"jpms-ok".equals(decoded.value())) {
            throw new IllegalStateException("JPMS mapper round trip returned an unexpected value");
        }
        ToolResult bound = new ToolBinding<>(input, (payload, _) -> ToolHandlers.completed(ToolResult.text(payload.value()))).invoke(mapper, Map.of("value", "binding-ok"), ToolCancellation.none()).toCompletableFuture().resultNow();
        if (bound.isError() || !"binding-ok".equals(bound.content().getFirst().text())) {
            throw new IllegalStateException("JPMS typed binding returned an unexpected result");
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