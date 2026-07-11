package dev.mcdevmcp.mcp;

import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.AppVersion;
import dev.mcdevmcp.support.JsonResourceReader;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

public final class McpServerFactory {
    private final AppEnvironment environment;
    private final Map<String, ToolBinding<?>> bindings;
    private final ResourceCatalog resourceCatalog;
    private final McpJsonMapper mapper;

    public McpServerFactory(AppEnvironment environment) {
        this(environment, Map.of(), McpJsonDefaults.getMapper());
    }
    
    McpServerFactory(AppEnvironment environment, Map<String, ToolBinding<?>> bindings, McpJsonMapper mapper) {
        this(environment, bindings, new ResourceCatalog(new JsonResourceReader(mapper)), mapper);
    }

    McpServerFactory(AppEnvironment environment, Map<String, ToolBinding<?>> bindings, ResourceCatalog resourceCatalog, McpJsonMapper mapper) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.bindings = Map.copyOf(bindings);
        this.resourceCatalog = Objects.requireNonNull(resourceCatalog, "resourceCatalog");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }
    
    public StdioServer startStdio(InputStream input, OutputStream output) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        
        var inputClosed = new CountDownLatch(1);
        McpJsonMapper transportMapper = new NodeParityJsonMapper(mapper);
        var transport = new StdioServerTransportProvider(transportMapper, new EofTrackingInputStream(input, inputClosed), new NonClosingOutputStream(output));
        var blockingExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            ToolCatalog toolCatalog = ToolCatalog.load(environment, bindings, mapper);
            var adapter = new McpSdkAdapter(mapper);
            McpAsyncServer server = McpServer.async(transport).jsonMapper(transportMapper).serverInfo("mcdev-mcp", AppVersion.current()).instructions(ResourceCatalog.INSTRUCTIONS).capabilities(McpSchema.ServerCapabilities.builder().resources(null, null).tools(null).build()).validateToolInputs(true).tools(adapter.tools(toolCatalog)).resources(adapter.resources(resourceCatalog)).build();
            return new StdioServer(server, blockingExecutor, inputClosed);
        } catch (RuntimeException | Error exception) {
            blockingExecutor.close();
            throw exception;
        }
    }
    
}
