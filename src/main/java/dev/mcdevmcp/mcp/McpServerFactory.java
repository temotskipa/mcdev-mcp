package dev.mcdevmcp.mcp;

import dev.mcdevmcp.bridge.BridgeSession;
import dev.mcdevmcp.mcp.resource.ResourceCatalog;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.transport.McpSdkAdapter;
import dev.mcdevmcp.mcp.transport.StdioServer;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.DebugLog;
import dev.mcdevmcp.tools.runtime.RuntimeToolModule;
import dev.mcdevmcp.tools.statictool.StaticToolModule;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class McpServerFactory {
    private static final AutoCloseable NO_RUNTIME = () -> {
    };

    private final AppEnvironment environment;
    private final Map<String, ToolBinding<?>> bindings;
    private final ResourceCatalog resourceCatalog;
    private final McpJsonMapper mapper;
    private final AutoCloseable ownedRuntime;
    private final AtomicBoolean started = new AtomicBoolean();

    public McpServerFactory(AppEnvironment environment) {
        this(environment, defaultComposition(environment));
    }

    private McpServerFactory(AppEnvironment environment, DefaultComposition composition) {
        this(environment, composition.bindings(), composition.resourceCatalog(), composition.mapper(), composition.ownedRuntime());
    }

    McpServerFactory(AppEnvironment environment, Map<String, ToolBinding<?>> bindings, McpJsonMapper mapper) {
        this(environment, bindings, ResourceCatalog.withMapper(mapper), mapper, NO_RUNTIME);
    }

    McpServerFactory(AppEnvironment environment, Map<String, ToolBinding<?>> bindings, ResourceCatalog resourceCatalog, McpJsonMapper mapper) {
        this(environment, bindings, resourceCatalog, mapper, NO_RUNTIME);
    }

    McpServerFactory(AppEnvironment environment, Map<String, ToolBinding<?>> bindings, ResourceCatalog resourceCatalog, McpJsonMapper mapper, AutoCloseable ownedRuntime) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.bindings = Map.copyOf(bindings);
        this.resourceCatalog = Objects.requireNonNull(resourceCatalog, "resourceCatalog");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.ownedRuntime = Objects.requireNonNull(ownedRuntime, "ownedRuntime");
    }

    private static DefaultComposition defaultComposition(AppEnvironment environment) {
        Objects.requireNonNull(environment, "environment");
        McpJsonMapper mapper = McpJsonDefaults.getMapper();
        PlatformPaths paths = PlatformPaths.forEnvironment(System.getProperty("os.name"), environment.values(), Path.of(System.getProperty("user.home")));
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var session = new BridgeSession(client, mapper, environment, message -> DebugLog.write(environment, message));
        var ownedRuntime = new RuntimeResources(session, client);
        try {
            var bindings = new LinkedHashMap<>(StaticToolModule.handlers(paths));
            RuntimeToolModule.handlers(session, mapper).forEach((name, binding) -> {
                if (bindings.putIfAbsent(name, binding) != null) {
                    throw new IllegalStateException("Duplicate MCP tool binding: " + name);
                }
            });
            return new DefaultComposition(bindings, ResourceCatalog.withMapper(mapper), mapper, ownedRuntime);
        } catch (RuntimeException | Error exception) {
            closeAfterFailure(ownedRuntime, exception);
            throw exception;
        }
    }

    private static void closeAfterFailure(AutoCloseable closeable, Throwable failure) {
        try {
            closeable.close();
        } catch (Throwable closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    ToolCatalog loadToolCatalog(ExecutorService blockingExecutor) {
        Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        return ToolCatalog.load(environment, bindings, mapper, blockingExecutor);
    }

    public StdioServer startStdio(InputStream input, OutputStream output) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("MCP server factory can only start one STDIO server");
        }

        var blockingExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            ToolCatalog toolCatalog = loadToolCatalog(blockingExecutor);
            return McpSdkAdapter.startStdio(mapper, input, output, toolCatalog, resourceCatalog, blockingExecutor, ownedRuntime);
        } catch (RuntimeException | Error exception) {
            closeAfterFailure(ownedRuntime, exception);
            closeAfterFailure(blockingExecutor, exception);
            throw exception;
        }
    }

    private record DefaultComposition(Map<String, ToolBinding<?>> bindings, ResourceCatalog resourceCatalog, McpJsonMapper mapper, AutoCloseable ownedRuntime) {
    }

    private record RuntimeResources(BridgeSession session, HttpClient client) implements AutoCloseable {
        @Override
        public void close() {
            try {
                session.close();
            } finally {
                client.close();
            }
        }
    }
}
