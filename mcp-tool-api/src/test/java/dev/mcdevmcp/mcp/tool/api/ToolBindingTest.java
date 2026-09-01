package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolBindingTest {
    private static final ToolInput<BindingArguments> INPUT = ToolInput.of(BindingArguments.class, RecordInputSchemaFactory.standard());

    @Test
    void decodesTheCompleteMapBeforeInvokingTheTypedHandler() {
        var received = new AtomicReference<BindingArguments>();
        var binding = new ToolBinding<>(INPUT, (arguments, _) -> {
            received.set(arguments);
            return ToolHandlers.completed(ToolResult.text("ok"));
        });

        ToolResult result = binding.invoke(McpJsonDefaults.getMapper(), Map.of("value", "typed"), ToolCancellation.none()).toCompletableFuture().resultNow();

        assertEquals(new BindingArguments("typed"), received.get());
        assertFalse(result.isError());
    }

    @Test
    void propagatesSynchronousCompatibilityDecodeFailureBeforeCallingTheHandler() {
        var handlerCalled = new AtomicBoolean();
        var binding = ToolBinding.compatibility((_, _) -> {
            throw new IllegalArgumentException("bad arguments");
        }, (BindingArguments _, ToolCancellation _) -> {
            handlerCalled.set(true);
            return ToolHandlers.completed(ToolResult.text("unexpected"));
        });

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> binding.invoke(McpJsonDefaults.getMapper(), Map.of(), ToolCancellation.none()));

        assertEquals("bad arguments", exception.getMessage());
        assertFalse(handlerCalled.get());
    }

    @Test
    void preservesAsynchronousHandlerFailure() {
        var binding = new ToolBinding<>(INPUT, (_, _) -> CompletableFuture.failedFuture(new IllegalStateException("async failure")));

        CompletionException exception = assertThrows(CompletionException.class, () -> binding.invoke(McpJsonDefaults.getMapper(), Map.of("value", "typed"), ToolCancellation.none()).toCompletableFuture().join());

        assertEquals("async failure", exception.getCause().getMessage());
    }

    @Test
    void blockingBindingRunsOnVirtualThreadAndFutureCancellationInterruptsIt() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var virtualThread = new AtomicBoolean();
        var binding = ToolBinding.blocking(INPUT, (_, _) -> {
            virtualThread.set(Thread.currentThread().isVirtual());
            started.countDown();
            try {
                Thread.sleep(Duration.ofMinutes(1));
                return ToolResult.text("unexpected");
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw exception;
            }
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = binding.withBlockingExecutor(executor).invoke(McpJsonDefaults.getMapper(), Map.of("value", "typed"), ToolCancellation.none()).toCompletableFuture();

            assertTrue(started.await(5, TimeUnit.SECONDS), "blocking binding did not start");
            assertTrue(future.cancel(true), "blocking binding future was not cancelled");
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "cancellation did not interrupt the virtual thread");
            assertTrue(virtualThread.get(), "blocking binding did not run on a virtual thread");
        }
    }
}