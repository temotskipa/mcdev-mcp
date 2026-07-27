package dev.mcdevmcp.mcp;

import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.mcp.resource.ResourceCatalog;
import dev.mcdevmcp.mcp.tool.TestEmptyArguments;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.ToolHandlers;
import dev.mcdevmcp.mcp.tool.ToolResult;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class McpServerFactoryTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

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

    @Test
    void stdioServerOwnsTheRuntimeAndFactoryStartsOnlyOnce() {
        var runtimeCloses = new AtomicInteger();
        var factory = new McpServerFactory(new AppEnvironment(Map.of()), Map.of(), ResourceCatalog.withMapper(MAPPER), MAPPER, runtimeCloses::incrementAndGet);
        var server = factory.startStdio(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());

        server.close();
        server.close();

        assertEquals(1, runtimeCloses.get());
        assertThrows(IllegalStateException.class, () -> factory.startStdio(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
    }

    @Test
    void startupFailureClosesTheOwnedRuntime() {
        var runtimeCloses = new AtomicInteger();
        var unexpectedBinding = new ToolBinding<>(ArgumentDecoder.sdk(TestEmptyArguments.class), (_, _) -> ToolHandlers.completed(ToolResult.text("unused")));
        var factory = new McpServerFactory(new AppEnvironment(Map.of()), Map.of("not_in_metadata", unexpectedBinding), ResourceCatalog.withMapper(MAPPER), MAPPER, runtimeCloses::incrementAndGet);

        assertThrows(IllegalArgumentException.class, () -> factory.startStdio(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
        assertEquals(1, runtimeCloses.get());
    }

    @Test
    void startupFailurePreservesTheOriginalFailureWhenRuntimeCloseThrowsAnError() {
        var unexpectedBinding = new ToolBinding<>(ArgumentDecoder.sdk(TestEmptyArguments.class), (_, _) -> ToolHandlers.completed(ToolResult.text("unused")));
        var factory = new McpServerFactory(new AppEnvironment(Map.of()), Map.of("not_in_metadata", unexpectedBinding), ResourceCatalog.withMapper(MAPPER), MAPPER, () -> {
            throw new AssertionError("runtime close failed");
        });

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> factory.startStdio(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));

        assertEquals(1, failure.getSuppressed().length);
        assertEquals("runtime close failed", failure.getSuppressed()[0].getMessage());
    }
}
