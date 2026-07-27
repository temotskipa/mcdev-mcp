package dev.mcdevmcp.mcp.transport;

import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.mcp.resource.ResourceCatalog;
import dev.mcdevmcp.mcp.tool.*;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
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
            assertFalse(result.isError());
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
            assertFalse(result.isError());
        }
    }

}
