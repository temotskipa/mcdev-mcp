package dev.mcdevmcp.mcp;

import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.*;
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
    void factoryAdaptsABlockingTypedBindingForItsToolCatalog() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var virtualThread = new AtomicReference<Thread>();
        var binding = ToolBinding.blocking(ArgumentDecoder.sdk(TestEmptyArguments.class), (_, _) -> {
            virtualThread.set(Thread.currentThread());
            started.countDown();
            try {
                Thread.sleep(java.time.Duration.ofMinutes(1));
                return ToolResult.text("unexpected");
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw exception;
            }
        });
        var factory = new McpServerFactory(new AppEnvironment(Map.of()), Map.of("mc_version", binding), MAPPER);
        
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = factory.loadToolCatalog(executor).dispatch("mc_version", Map.of(), Cancellation.none()).toCompletableFuture();
            
            assertTrue(started.await(5, TimeUnit.SECONDS), "factory did not start the blocking binding");
            assertTrue(future.cancel(true), "factory binding future was not cancelled");
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "factory binding cancellation did not interrupt execution");
            assertTrue(virtualThread.get().isVirtual());
        }
    }
}
