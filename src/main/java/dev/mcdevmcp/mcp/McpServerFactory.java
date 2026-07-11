package dev.mcdevmcp.mcp;

import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.AppVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class McpServerFactory {
    private final AppEnvironment environment;
    private final Map<String, ToolHandler> asyncHandlers;
    private final Map<String, BlockingToolHandler> blockingHandlers;
    private final ResourceCatalog resourceCatalog;
    
    public McpServerFactory(AppEnvironment environment) {
        this(environment, Map.of(), Map.of(), new ResourceCatalog());
    }
    
    McpServerFactory(AppEnvironment environment, Map<String, ToolHandler> asyncHandlers, Map<String, BlockingToolHandler> blockingHandlers, ResourceCatalog resourceCatalog) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.asyncHandlers = Map.copyOf(asyncHandlers);
        this.blockingHandlers = Map.copyOf(blockingHandlers);
        this.resourceCatalog = Objects.requireNonNull(resourceCatalog, "resourceCatalog");
    }
    
    public StdioServer startStdio(InputStream input, OutputStream output) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        
        var inputClosed = new CountDownLatch(1);
        McpJsonMapper jsonMapper = new NodeParityJsonMapper(McpJsonDefaults.getMapper());
        var transport = new StdioServerTransportProvider(jsonMapper, new EofTrackingInputStream(input, inputClosed), new NonClosingOutputStream(output));
        ExecutorService blockingExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            ToolCatalog toolCatalog = ToolCatalog.load(environment, bindHandlers(blockingExecutor));
            var adapter = new McpSdkAdapter();
            McpAsyncServer server = McpServer.async(transport).jsonMapper(jsonMapper).serverInfo("mcdev-mcp", AppVersion.current()).instructions(ResourceCatalog.INSTRUCTIONS).capabilities(McpSchema.ServerCapabilities.builder().resources(null, null).tools(null).build()).validateToolInputs(true).tools(adapter.tools(toolCatalog)).resources(adapter.resources(resourceCatalog)).build();
            return new StdioServer(server, blockingExecutor, inputClosed);
        } catch (RuntimeException | Error exception) {
            blockingExecutor.close();
            throw exception;
        }
    }
    
    private Map<String, ToolHandler> bindHandlers(ExecutorService blockingExecutor) {
        Map<String, ToolHandler> handlers = new LinkedHashMap<>(asyncHandlers);
        blockingHandlers.forEach((name, handler) -> {
            if (handlers.putIfAbsent(name, ToolHandlers.blocking(blockingExecutor, handler)) != null) {
                throw new IllegalArgumentException("Duplicate tool handler: " + name);
            }
        });
        return Map.copyOf(handlers);
    }
}
