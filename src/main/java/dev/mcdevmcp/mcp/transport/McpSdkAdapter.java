package dev.mcdevmcp.mcp.transport;

import dev.mcdevmcp.mcp.resource.ResourceCatalog;
import dev.mcdevmcp.mcp.resource.ResourceRead;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.ToolContent;
import dev.mcdevmcp.mcp.tool.ToolDefinition;
import dev.mcdevmcp.mcp.tool.ToolResult;
import dev.mcdevmcp.support.AppVersion;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/**
 * The only production boundary that exposes Reactor types.
 */
public final class McpSdkAdapter {
    private final McpJsonMapper mapper;
    private final ExecutorService blockingExecutor;

    McpSdkAdapter(McpJsonMapper mapper, ExecutorService blockingExecutor) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.blockingExecutor = Objects.requireNonNull(blockingExecutor, "blockingExecutor");
    }

    static McpJsonMapper nodeParityMapper(McpJsonMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return new NodeParityJsonMapper(mapper);
    }

    static StdioServerTransportProvider stdioTransport(McpJsonMapper mapper, InputStream input, OutputStream output, CountDownLatch inputClosed) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(inputClosed, "inputClosed");
        return new StdioServerTransportProvider(mapper, new EofTrackingInputStream(input, inputClosed), new NonClosingOutputStream(output));
    }

    public static StdioServer startStdio(McpJsonMapper mapper, InputStream input, OutputStream output, ToolCatalog toolCatalog, ResourceCatalog resourceCatalog, ExecutorService blockingExecutor) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(toolCatalog, "toolCatalog");
        Objects.requireNonNull(resourceCatalog, "resourceCatalog");
        Objects.requireNonNull(blockingExecutor, "blockingExecutor");

        var inputClosed = new CountDownLatch(1);
        McpJsonMapper transportMapper = nodeParityMapper(mapper);
        var transport = stdioTransport(transportMapper, input, output, inputClosed);
        var adapter = new McpSdkAdapter(mapper, blockingExecutor);
        McpAsyncServer server = McpServer.async(transport).jsonMapper(transportMapper).serverInfo("mcdev-mcp", AppVersion.current()).instructions(ResourceCatalog.INSTRUCTIONS).capabilities(McpSchema.ServerCapabilities.builder().resources(null, null).tools(null).build()).validateToolInputs(true).tools(adapter.tools(toolCatalog)).resources(adapter.resources(resourceCatalog)).build();
        return new StdioServer(server, blockingExecutor, inputClosed);
    }

    List<McpServerFeatures.AsyncToolSpecification> tools(ToolCatalog catalog) {
        return catalog.enabledDefinitions().stream().map(definition -> McpServerFeatures.AsyncToolSpecification.builder().tool(toSdkTool(definition)).callHandler(callHandler(definition)).build()).toList();
    }

    List<McpServerFeatures.AsyncResourceSpecification> resources(ResourceCatalog catalog) {
        return catalog.definitions().stream().map(definition -> new McpServerFeatures.AsyncResourceSpecification(McpSchema.Resource.builder(definition.uri().toString(), definition.name()).title(definition.title()).description(definition.description()).mimeType(definition.mimeType()).build(), (_, request) -> readResource(catalog, URI.create(request.uri())))).toList();
    }

    BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler(ToolDefinition definition) {
        return (_, request) -> invoke(definition, request);
    }

    private Mono<McpSchema.ReadResourceResult> readResource(ResourceCatalog catalog, URI uri) {
        return Mono.defer(() -> {
            var result = new CompletableFuture<McpSchema.ReadResourceResult>();
            Future<?> task;
            try {
                task = blockingExecutor.submit(() -> {
                    try {
                        ResourceRead read = catalog.read(uri);
                        var contents = McpSchema.TextResourceContents.builder(read.uri().toString(), read.text()).mimeType(read.mimeType()).build();
                        result.complete(McpSchema.ReadResourceResult.builder(List.of(contents)).build());
                    } catch (Throwable exception) {
                        result.completeExceptionally(exception);
                        if (exception instanceof Error error) {
                            throw error;
                        }
                    }
                });
            } catch (RuntimeException exception) {
                return Mono.error(exception);
            }
            result.whenComplete((_, _) -> {
                if (result.isCancelled()) {
                    task.cancel(true);
                }
            });
            return Mono.fromFuture(result);
        });
    }

    private Mono<McpSchema.CallToolResult> invoke(ToolDefinition definition, McpSchema.CallToolRequest request) {
        return Mono.defer(() -> {
            var cancelled = new AtomicBoolean();
            CompletionStage<ToolResult> stage;
            try {
                Map<String, Object> arguments = request.arguments();
                stage = Objects.requireNonNull(definition.binding().invoke(mapper, arguments == null ? Map.of() : arguments, cancelled::get), "Tool handler returned null: " + definition.name());
            } catch (RuntimeException exception) {
                return Mono.just(error(definition.name(), exception));
            }

            CompletableFuture<ToolResult> future;
            try {
                future = stage.toCompletableFuture();
            } catch (RuntimeException exception) {
                return Mono.just(error(definition.name(), exception));
            }

            return Mono.fromFuture(future).map(this::toSdkResult).onErrorResume(exception -> Mono.just(error(definition.name(), exception))).doOnCancel(() -> {
                cancelled.set(true);
                future.cancel(true);
            });
        });
    }

    private McpSchema.Tool toSdkTool(ToolDefinition definition) {
        return McpSchema.Tool.builder(definition.name(), definition.inputSchema()).description(definition.description()).build();
    }

    private McpSchema.CallToolResult toSdkResult(ToolResult result) {
        return McpSchema.CallToolResult.builder(result.content().stream().map(this::toSdkContent).toList()).isError(result.isError()).build();
    }

    private McpSchema.Content toSdkContent(ToolContent content) {
        return switch (content.type()) {
            case TEXT -> McpSchema.TextContent.builder(content.text()).build();
            case IMAGE -> McpSchema.ImageContent.builder(content.data(), content.mimeType()).build();
            case AUDIO -> McpSchema.AudioContent.builder(content.data(), content.mimeType()).build();
        };
    }

    private McpSchema.CallToolResult error(String name, Throwable exception) {
        return toSdkResult(ToolResult.error(ToolCatalog.errorText(name, exception)));
    }
}
