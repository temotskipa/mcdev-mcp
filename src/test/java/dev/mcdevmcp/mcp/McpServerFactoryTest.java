package dev.mcdevmcp.mcp;

import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.TestEmptyArguments;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.ToolResult;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
