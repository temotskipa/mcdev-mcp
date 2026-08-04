package dev.mcdevmcp.mcp.transport;

import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.mcp.ServerDefinition;
import dev.mcdevmcp.mcp.resource.ResourceCatalog;
import dev.mcdevmcp.mcp.tool.*;
import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class McpSdkAdapterTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    @Test
    void resourceReadRunsOnTheSuppliedVirtualExecutorInsteadOfTheSubscribingThread() throws Exception {
        var executingThread = new AtomicReference<Thread>();
        ThreadFactory recordingFactory = runnable -> Thread.ofVirtual().name("mcp-resource-", 0).unstarted(() -> {
            executingThread.set(Thread.currentThread());
            runnable.run();
        });

        try (var executor = Executors.newThreadPerTaskExecutor(recordingFactory)) {
            var adapter = new McpSdkAdapter(MAPPER, executor);
            McpServerFeatures.AsyncResourceSpecification resource = adapter.resources(new ResourceCatalog()).getFirst();
            var subscribingThread = Thread.currentThread();
            var result = resource.readHandler().apply(null, McpSchema.ReadResourceRequest.builder("mcdev://guides/python-scripting").build()).toFuture().get(5, TimeUnit.SECONDS);

            assertEquals("mcdev://guides/python-scripting", result.contents().getFirst().uri());
            assertTrue(executingThread.get().isVirtual());
            assertNotEquals(subscribingThread, executingThread.get());
        }
    }

    @Test
    void nullableSdkArgumentsReachTheTypedBindingAsAnEmptyRecord() throws Exception {
        var received = new CompletableFuture<TestEmptyArguments>();
        var definition = new ToolDefinition("mc_list_packages", "legacy optional arguments", Map.of("type", "object", "properties", Map.of()), new ToolBinding<>(ArgumentDecoder.sdk(TestEmptyArguments.class), (arguments, _) -> {
            received.complete(arguments);
            return ToolHandlers.completed(ToolResult.text("legacy packages"));
        }), ToolAvailability.ALWAYS);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = new McpSdkAdapter(MAPPER, executor).callHandler(definition).apply(null, McpSchema.CallToolRequest.builder("mc_list_packages").build()).toFuture().get(5, TimeUnit.SECONDS);

            assertEquals(new TestEmptyArguments(), received.get(5, TimeUnit.SECONDS));
            assertNull(result.isError());
            assertEquals("legacy packages", assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
        }
    }

    @Test
    void imageContentMapsToTheSdkProtocolTypeWithoutDecodingBase64() throws Exception {
        var definition = new ToolDefinition("image", "image", Map.of("type", "object"), new ToolBinding<>(ArgumentDecoder.sdk(TestEmptyArguments.class), (_, _) -> ToolHandlers.completed(new ToolResult(List.of(ToolContent.image("iVBORw0KGgo=", "image/png")), false))), ToolAvailability.ALWAYS);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = new McpSdkAdapter(MAPPER, executor).callHandler(definition).apply(null, McpSchema.CallToolRequest.builder("image").arguments(Map.of()).build()).toFuture().get(5, TimeUnit.SECONDS);

            var image = assertInstanceOf(McpSchema.ImageContent.class, result.content().getFirst());
            assertEquals("iVBORw0KGgo=", image.data());
            assertEquals("image/png", image.mimeType());
            assertNull(result.isError());
        }
    }

    @Test
    void streamableConstructionFailureClosesTransportAndSuppressesCloseFailure() {
        var transport = new RecordingStreamableTransport(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ServerDefinition definition = serverDefinition();
            var duplicate = new McpSdkAdapter(MAPPER, executor).tools(definition.tools()).getFirst();
            var extensions = new McpSdkAdapter.AsyncServerExtensions(List.of(duplicate), List.of(), List.of(), List.of(), List.of(), McpSdkAdapter.AsyncServerExtensions.production().capabilities(), Duration.ofSeconds(1));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> McpSdkAdapter.startStreamable(MAPPER, transport, definition, executor, extensions));

            assertTrue(failure.getMessage().startsWith("Duplicate MCP tool name:"));
            assertEquals(1, transport.closeCalls.get());
            assertEquals(1, failure.getSuppressed().length);
            assertEquals("close failure 1", failure.getSuppressed()[0].getMessage());
        }
    }

    @Test
    void streamableCloseIsIdempotentAndPreservesBothCloseFailures() {
        var transport = new RecordingStreamableTransport(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = McpSdkAdapter.startStreamable(MAPPER, transport, serverDefinition(), executor);

            IllegalStateException failure = assertThrows(IllegalStateException.class, server::close);
            server.close();

            assertEquals(2, transport.closeCalls.get());
            assertEquals("close failure 1", failure.getCause().getMessage());
            assertTrue(Arrays.stream(failure.getCause().getSuppressed()).anyMatch(exception -> "close failure 2".equals(exception.getMessage())));
        }
    }

    @Test
    void streamableExtensionsCannotHideProductionToolsAndResources() {
        var transport = new RecordingStreamableTransport(false);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var requestedCapabilities = McpSchema.ServerCapabilities.builder().logging().build();
            var extensions = new McpSdkAdapter.AsyncServerExtensions(List.of(), List.of(), List.of(), List.of(), List.of(), requestedCapabilities, Duration.ofSeconds(1));

            try (var server = McpSdkAdapter.startStreamable(MAPPER, transport, serverDefinition(), executor, extensions)) {
                McpSchema.ServerCapabilities capabilities = server.server().getServerCapabilities();
                assertNotNull(capabilities.logging());
                assertNotNull(capabilities.tools());
                assertNotNull(capabilities.resources());
            }
        }
    }

    private static ServerDefinition serverDefinition() {
        var bindings = CompleteToolBindings.including(MAPPER, Map.of());
        var tools = ToolCatalog.load(new AppEnvironment(Map.of()), bindings, MAPPER);
        return new ServerDefinition("test", "1", "test", tools, ResourceCatalog.withMapper(MAPPER));
    }

    private static final class RecordingStreamableTransport implements McpStreamableServerTransportProvider {
        private final boolean failClose;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private RecordingStreamableTransport(boolean failClose) {
            this.failClose = failClose;
        }

        @Override
        public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
        }

        @Override
        public Mono<Void> notifyClients(String method, Object params) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.defer(() -> {
                int call = closeCalls.incrementAndGet();
                return failClose ? Mono.error(new IllegalStateException("close failure " + call)) : Mono.empty();
            });
        }
    }

}
