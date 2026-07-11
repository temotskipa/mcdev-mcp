package dev.mcdevmcp.mcp;

import io.modelcontextprotocol.server.McpAsyncServer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

public final class StdioServer implements AutoCloseable {
    private final McpAsyncServer server;
    private final ExecutorService blockingExecutor;
    private final CountDownLatch inputClosed;
    
    StdioServer(McpAsyncServer server, ExecutorService blockingExecutor, CountDownLatch inputClosed) {
        this.server = server;
        this.blockingExecutor = blockingExecutor;
        this.inputClosed = inputClosed;
    }
    
    public void awaitInputClosed() throws InterruptedException {
        inputClosed.await();
    }
    
    @Override
    public void close() {
        server.close();
        blockingExecutor.close();
    }
}
