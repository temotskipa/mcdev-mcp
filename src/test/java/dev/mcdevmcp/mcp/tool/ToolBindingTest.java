package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.mcp.transport.SdkJsonMode;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.JsonValues;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ToolBindingTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    @Test
    void decodesTheWholeArgumentMapOnceAndConvertsWireValuesToDomainValues() {
        var options = new LinkedHashMap<String, Object>();
        options.put("enabled", true);
        options.put("missing", null);
        options.put("values", new ArrayList<>(List.of("one", 2L)));
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("uri", "https://example.test/tool");
        arguments.put("path", "build/output.txt");
        arguments.put("timeoutMs", 1250L);
        arguments.put("startedAt", "2026-07-10T12:34:56Z");
        arguments.put("mode", "SAFE");
        arguments.put("options", options);
        var mapper = new CountingMcpJsonMapper(MAPPER);
        var received = new CompletableFuture<DomainArguments>();
        var binding = new ToolBinding<>(ArgumentDecoder.sdk(WireArguments.class).map(wire -> new DomainArguments(URI.create(wire.uri()), Path.of(wire.path()), Duration.ofMillis(wire.timeoutMs()), wire.startedAt(), wire.mode(), JsonValues.freezeMap(wire.options()))), (domain, _) -> {
            received.complete(domain);
            return ToolHandlers.completed(ToolResult.text("ok"));
        });

        var result = binding.invoke(mapper, arguments, Cancellation.none()).toCompletableFuture().resultNow();
        var domain = received.resultNow();

        assertFalse(result.isError());
        assertEquals(1, mapper.convertValueCalls());
        assertEquals(URI.create("https://example.test/tool"), domain.uri());
        assertEquals(Path.of("build/output.txt"), domain.path());
        assertEquals(Duration.ofMillis(1250), domain.timeout());
        assertEquals(Instant.parse("2026-07-10T12:34:56Z"), domain.startedAt());
        assertEquals(SdkJsonMode.SAFE, domain.mode());
        assertNull(domain.options().get("missing"));
        assertThrows(UnsupportedOperationException.class, () -> domain.options().put("later", false));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) domain.options().get("values")).clear());
    }

    @Test
    void propagatesSynchronousDecoderFailureBeforeCallingTheHandler() {
        var handlerCalled = new CompletableFuture<Void>();
        var binding = new ToolBinding<TestEmptyArguments>((_, _) -> {
            throw new IllegalArgumentException("bad arguments");
        }, (_, _) -> {
            handlerCalled.complete(null);
            return ToolHandlers.completed(ToolResult.text("unexpected"));
        });

        var exception = assertThrows(IllegalArgumentException.class, () -> binding.invoke(MAPPER, Map.of(), Cancellation.none()));

        assertEquals("bad arguments", exception.getMessage());
        assertFalse(handlerCalled.isDone());
    }

    @Test
    void preservesAsynchronousHandlerFailure() {
        var binding = new ToolBinding<>(ArgumentDecoder.sdk(TestEmptyArguments.class), (_, _) -> CompletableFuture.failedFuture(new IllegalStateException("async failure")));

        var exception = assertThrows(CompletionException.class, () -> binding.invoke(MAPPER, Map.of(), Cancellation.none()).toCompletableFuture().join());

        assertEquals("async failure", exception.getCause().getMessage());
    }

    @Test
    void blockingBindingRunsOnItsAssignedVirtualExecutorAndCancellationInterruptsIt() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var virtualThread = new AtomicBoolean();
        var binding = ToolBinding.blocking(ArgumentDecoder.sdk(TestEmptyArguments.class), (_, _) -> {
            virtualThread.set(Thread.currentThread().isVirtual());
            started.countDown();
            try {
                Thread.sleep(java.time.Duration.ofMinutes(1));
                return ToolResult.text("unexpected");
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw exception;
            }
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = binding.withBlockingExecutor(executor).invoke(MAPPER, Map.of(), Cancellation.none()).toCompletableFuture();

            assertTrue(started.await(5, TimeUnit.SECONDS), "blocking binding did not start");
            assertTrue(future.cancel(true), "blocking binding future was not cancelled");
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "cancellation did not interrupt the virtual thread");
            assertTrue(virtualThread.get(), "blocking binding did not run on a virtual thread");
        }
    }
}
