package dev.mcdevmcp.mcp;

import dev.mcdevmcp.mcp.resource.ResourceCatalog;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.transport.McpSdkAdapter;
import dev.mcdevmcp.mcp.transport.StdioServer;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.tools.statictool.StaticToolModule;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class McpServerFactory {
    private final AppEnvironment environment;
    private final Map<String, ToolBinding<?>> bindings;
    private final ResourceCatalog resourceCatalog;
    private final McpJsonMapper mapper;

    public McpServerFactory(AppEnvironment environment) {
        this(environment, staticBindings(environment), McpJsonDefaults.getMapper());
    }

    McpServerFactory(AppEnvironment environment, Map<String, ToolBinding<?>> bindings, McpJsonMapper mapper) {
        this(environment, bindings, ResourceCatalog.withMapper(mapper), mapper);
    }

    McpServerFactory(AppEnvironment environment, Map<String, ToolBinding<?>> bindings, ResourceCatalog resourceCatalog, McpJsonMapper mapper) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.bindings = Map.copyOf(bindings);
        this.resourceCatalog = Objects.requireNonNull(resourceCatalog, "resourceCatalog");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    private static Map<String, ToolBinding<?>> staticBindings(AppEnvironment environment) {
        PlatformPaths paths = PlatformPaths.forEnvironment(System.getProperty("os.name"), environment.values(), Path.of(System.getProperty("user.home")));
        return StaticToolModule.handlers(paths);
    }

    ToolCatalog loadToolCatalog(ExecutorService blockingExecutor) {
        Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        return ToolCatalog.load(environment, bindings, mapper, blockingExecutor);
    }

    public StdioServer startStdio(InputStream input, OutputStream output) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");

        var blockingExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            ToolCatalog toolCatalog = loadToolCatalog(blockingExecutor);
            return McpSdkAdapter.startStdio(mapper, input, output, toolCatalog, resourceCatalog, blockingExecutor);
        } catch (RuntimeException | Error exception) {
            blockingExecutor.close();
            throw exception;
        }
    }

}
