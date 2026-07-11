package dev.mcdevmcp.mcp;

import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class ToolBinding<A> {
    private final ArgumentDecoder<A> decoder;
    private final ToolHandler<A> handler;

    public ToolBinding(ArgumentDecoder<A> decoder, ToolHandler<A> handler) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public CompletionStage<ToolResult> invoke(McpJsonMapper mapper, Map<String, Object> arguments, Cancellation cancellation) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(cancellation, "cancellation");
        A decoded = decoder.decode(mapper, arguments);
        return Objects.requireNonNull(handler.handle(decoded, cancellation), "Tool handler returned null");
    }
}
