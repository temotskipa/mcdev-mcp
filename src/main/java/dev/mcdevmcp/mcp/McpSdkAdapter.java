package dev.mcdevmcp.mcp;

import dev.mcdevmcp.support.JsonSupport;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/**
 * The only production boundary that exposes Reactor types.
 */
public final class McpSdkAdapter {
    public List<McpServerFeatures.AsyncToolSpecification> tools(ToolCatalog catalog) {
        return catalog.enabledDefinitions().stream().map(definition -> McpServerFeatures.AsyncToolSpecification.builder().tool(toSdkTool(definition)).callHandler(callHandler(definition)).build()).toList();
    }
    
    public List<McpServerFeatures.AsyncResourceSpecification> resources(ResourceCatalog catalog) {
        return catalog.definitions().stream().map(definition -> new McpServerFeatures.AsyncResourceSpecification(McpSchema.Resource.builder(definition.uri().toString(), definition.name()).title(definition.title()).description(definition.description()).mimeType(definition.mimeType()).build(), (_, request) -> readResource(catalog, URI.create(request.uri())))).toList();
    }
    
    BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler(ToolDefinition definition) {
        return (_, request) -> invoke(definition, request);
    }
    
    private Mono<McpSchema.ReadResourceResult> readResource(ResourceCatalog catalog, URI uri) {
        return Mono.fromSupplier(() -> {
            ResourceRead read = catalog.read(uri);
            var contents = McpSchema.TextResourceContents.builder(read.uri().toString(), read.text()).mimeType(read.mimeType()).build();
            return McpSchema.ReadResourceResult.builder(List.of(contents)).build();
        });
    }
    
    private Mono<McpSchema.CallToolResult> invoke(ToolDefinition definition, McpSchema.CallToolRequest request) {
        return Mono.defer(() -> {
            var cancelled = new AtomicBoolean();
            CompletionStage<ToolResult> stage;
            try {
                stage = Objects.requireNonNull(definition.handler().handle(JsonSupport.toJsonObject(request.arguments()), cancelled::get), "Tool handler returned null: " + definition.name());
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
        return McpSchema.Tool.builder(definition.name(), JsonSupport.toMap(definition.inputSchema())).description(definition.description()).build();
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
