package dev.mcdevmcp.mcp.transport;

import io.modelcontextprotocol.server.McpAsyncServer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StdioServer implements AutoCloseable {
    private final McpAsyncServer server;
    private final ExecutorService blockingExecutor;
    private final CountDownLatch inputClosed;
    private final AutoCloseable ownedRuntime;
    private final AtomicBoolean closed = new AtomicBoolean();

    StdioServer(McpAsyncServer server, ExecutorService blockingExecutor, CountDownLatch inputClosed, AutoCloseable ownedRuntime) {
        this.server = server;
        this.blockingExecutor = blockingExecutor;
        this.inputClosed = inputClosed;
        this.ownedRuntime = ownedRuntime;
    }

    private static Throwable close(AutoCloseable closeable, Throwable failure) {
        try {
            closeable.close();
        } catch (Throwable exception) {
            if (failure == null) {
                return exception;
            }
            failure.addSuppressed(exception);
        }
        return failure;
    }

    public void awaitInputClosed() throws InterruptedException {
        inputClosed.await();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Throwable failure = null;
        try {
            server.close();
        } catch (Throwable exception) {
            failure = exception;
        }
        failure = close(ownedRuntime, failure);
        failure = close(blockingExecutor, failure);
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure != null) {
            throw new IllegalStateException("Unable to close MCP server", failure);
        }
    }
}
