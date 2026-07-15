package dev.mcdevmcp.mcp;

import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;

public final class ToolBinding<A> {
    private final ArgumentDecoder<A> decoder;
    private final ToolHandler<A> handler;
    private final BlockingToolHandler<A> blockingHandler;
    
    public ToolBinding(ArgumentDecoder<A> decoder, ToolHandler<A> handler) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.handler = Objects.requireNonNull(handler, "handler");
        blockingHandler = null;
    }
    
    private ToolBinding(ArgumentDecoder<A> decoder, BlockingToolHandler<A> blockingHandler) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        handler = null;
        this.blockingHandler = Objects.requireNonNull(blockingHandler, "blockingHandler");
    }
    
    public static <A> ToolBinding<A> blocking(ArgumentDecoder<A> decoder, BlockingToolHandler<A> handler) {
        return new ToolBinding<>(decoder, handler);
    }
    
    ToolBinding<A> withBlockingExecutor(ExecutorService executor) {
        if (blockingHandler == null) {
            return this;
        }
        return new ToolBinding<>(decoder, ToolHandlers.blocking(executor, blockingHandler));
    }
    
    public CompletionStage<ToolResult> invoke(McpJsonMapper mapper, Map<String, Object> arguments, Cancellation cancellation) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(cancellation, "cancellation");
        A decoded = decoder.decode(mapper, arguments);
        if (handler == null) {
            throw new IllegalStateException("Blocking tool binding has not been assigned an executor");
        }
        return Objects.requireNonNull(handler.handle(decoded, cancellation), "Tool handler returned null");
    }
}
