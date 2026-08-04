package dev.mcdevmcp.mcp;

import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.mcp.resource.ResourceCatalog;
import dev.mcdevmcp.mcp.tool.TestEmptyArguments;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.ToolHandlers;
import dev.mcdevmcp.mcp.tool.ToolMetadata;
import dev.mcdevmcp.mcp.tool.ToolResult;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class McpServerFactoryTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    private static Map<String, ToolBinding<?>> completeBindings(ToolBinding<?> versionBinding) {
        var bindings = new LinkedHashMap<String, ToolBinding<?>>();
        for (ToolMetadata metadata : ToolCatalog.loadMetadata(MAPPER)) {
            bindings.put(metadata.name(), new ToolBinding<>(ArgumentDecoder.sdk(TestEmptyArguments.class), (_, _) -> ToolHandlers.completed(ToolResult.text("unused"))));
        }
        if (versionBinding != null) {
            bindings.put("mc_version", versionBinding);
        }
        return Map.copyOf(bindings);
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
        try (var factory = new McpServerFactory(new AppEnvironment(Map.of()), completeBindings(binding), MAPPER);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = factory.loadToolCatalog(executor).dispatch("mc_version", Map.of(), Cancellation.none()).toCompletableFuture();

            assertTrue(started.await(5, TimeUnit.SECONDS), "factory did not start the blocking binding");
            assertTrue(future.cancel(true), "factory binding future was not cancelled");
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "factory binding cancellation did not interrupt execution");
            assertTrue(virtualThread.get().isVirtual());
        }
    }

    @Test
    @SuppressWarnings("try")
    void stdioServerOwnsTheRuntimeAndFactoryStartsOnlyOnce() {
        var runtimeCloses = new AtomicInteger();
        try (var factory = new McpServerFactory(new AppEnvironment(Map.of()), completeBindings(null), ResourceCatalog.withMapper(MAPPER), MAPPER, runtimeCloses::incrementAndGet);
             var server = factory.startStdio(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.close();
            server.close();
            assertThrows(IllegalStateException.class, () -> factory.loadServerDefinition(executor));
            assertThrows(IllegalStateException.class, () -> factory.startStdio(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
        }
        assertEquals(1, runtimeCloses.get());
    }

    @Test
    @SuppressWarnings("try")
    void factoryCloseIsIdempotentAndRejectsFurtherUse() {
        var closes = new AtomicInteger();
        try (var factory = new McpServerFactory(new AppEnvironment(Map.of()), completeBindings(null), ResourceCatalog.withMapper(MAPPER), MAPPER, closes::incrementAndGet);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            factory.close();
            factory.close();
            assertThrows(IllegalStateException.class, () -> factory.loadServerDefinition(executor));
            assertThrows(IllegalStateException.class, () -> factory.startStdio(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
        }
        assertEquals(1, closes.get());
    }

    @Test
    void factoryAndServerShareOneRuntimeClose() {
        var closes = new AtomicInteger();
        try (var factory = new McpServerFactory(new AppEnvironment(Map.of()), completeBindings(null), ResourceCatalog.withMapper(MAPPER), MAPPER, closes::incrementAndGet);
             var server = factory.startStdio(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())) {
            assertNotNull(server);
        }
        assertEquals(1, closes.get());
    }

    @Test
    void startupFailureClosesTheOwnedRuntime() {
        var runtimeCloses = new AtomicInteger();
        var unexpectedBinding = new ToolBinding<>(ArgumentDecoder.sdk(TestEmptyArguments.class), (_, _) -> ToolHandlers.completed(ToolResult.text("unused")));
        try (var factory = new McpServerFactory(new AppEnvironment(Map.of()), Map.of("not_in_metadata", unexpectedBinding), ResourceCatalog.withMapper(MAPPER), MAPPER, runtimeCloses::incrementAndGet)) {
            assertThrows(IllegalArgumentException.class, () -> factory.startStdio(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                assertThrows(IllegalStateException.class, () -> factory.loadServerDefinition(executor));
            }
        }
        assertEquals(1, runtimeCloses.get());
    }

    @Test
    void startupFailurePreservesTheOriginalFailureWhenRuntimeCloseThrowsAnError() {
        var unexpectedBinding = new ToolBinding<>(ArgumentDecoder.sdk(TestEmptyArguments.class), (_, _) -> ToolHandlers.completed(ToolResult.text("unused")));
        try (var factory = new McpServerFactory(new AppEnvironment(Map.of()), Map.of("not_in_metadata", unexpectedBinding), ResourceCatalog.withMapper(MAPPER), MAPPER, () -> {
            throw new AssertionError("runtime close failed");
        })) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> factory.startStdio(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));

            assertEquals(1, failure.getSuppressed().length);
            assertEquals("runtime close failed", failure.getSuppressed()[0].getMessage());
        }
    }
}
