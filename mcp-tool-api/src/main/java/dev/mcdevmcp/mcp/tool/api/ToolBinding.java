package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.McpJsonMapper;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;

public final class ToolBinding<A> {
    private final ToolInput<A> input;
    private final ArgumentDecoder<A> decoder;
    private final ToolHandler<A> handler;
    private final BlockingToolHandler<A> blockingHandler;

    public ToolBinding(ToolInput<A> input, ToolHandler<A> handler) {
        this(Objects.requireNonNull(input, "input"), input::decode, handler, null);
    }

    private ToolBinding(ToolInput<A> input, ArgumentDecoder<A> decoder, ToolHandler<A> handler, BlockingToolHandler<A> blockingHandler) {
        this.input = input;
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.handler = handler;
        this.blockingHandler = blockingHandler;
        if ((handler == null) == (blockingHandler == null)) {
            throw new IllegalArgumentException("A tool binding must have exactly one handler");
        }
    }

    public static <A> ToolBinding<A> compatibility(ArgumentDecoder<A> decoder, ToolHandler<A> handler) {
        return new ToolBinding<>(null, decoder, Objects.requireNonNull(handler, "handler"), null);
    }

    public static <A> ToolBinding<A> blocking(ToolInput<A> input, BlockingToolHandler<A> handler) {
        return new ToolBinding<>(Objects.requireNonNull(input, "input"), input::decode, null, Objects.requireNonNull(handler, "handler"));
    }

    public static <A> ToolBinding<A> blockingCompatibility(ArgumentDecoder<A> decoder, BlockingToolHandler<A> handler) {
        return new ToolBinding<>(null, decoder, null, Objects.requireNonNull(handler, "handler"));
    }

    public Optional<ToolInput<A>> input() {
        return Optional.ofNullable(input);
    }

    public ToolBinding<A> withBlockingExecutor(ExecutorService executor) {
        if (blockingHandler == null) {
            return this;
        }
        return new ToolBinding<>(input, decoder, ToolHandlers.blocking(executor, blockingHandler), null);
    }

    public CompletionStage<ToolResult> invoke(McpJsonMapper mapper, Map<String, Object> arguments, ToolCancellation cancellation) {
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
