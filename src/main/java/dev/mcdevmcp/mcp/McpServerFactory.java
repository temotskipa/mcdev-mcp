package dev.mcdevmcp.mcp;

import dev.mcdevmcp.bridge.BridgeSession;
import dev.mcdevmcp.mcp.resource.ResourceCatalog;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.transport.McpSdkAdapter;
import dev.mcdevmcp.mcp.transport.StdioServer;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.AppVersion;
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

public final class McpServerFactory implements AutoCloseable {
    private static final Duration EXECUTOR_STOP_TIMEOUT = Duration.ofSeconds(5);

    private static final AutoCloseable NO_RUNTIME = () -> {
    };

    private final AppEnvironment environment;
    private final Map<String, ToolBinding<?>> bindings;
    private final ResourceCatalog resourceCatalog;
    private final McpJsonMapper mapper;
    private final CloseOnce ownedRuntime;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

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
        this.ownedRuntime = new CloseOnce(Objects.requireNonNull(ownedRuntime, "ownedRuntime"));
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
            RuntimeToolModule.handlers(session, mapper, environment).forEach((name, binding) -> {
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

    private static void closeExecutorAfterFailure(ExecutorService executor, Throwable failure) {
        executor.shutdownNow();
        boolean interrupted = false;
        try {
            if (!executor.awaitTermination(EXECUTOR_STOP_TIMEOUT.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS)) {
                failure.addSuppressed(new IllegalStateException("MCP blocking executor did not stop after startup failure"));
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            failure.addSuppressed(new IllegalStateException("Interrupted while stopping MCP blocking executor after startup failure", exception));
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    synchronized ToolCatalog loadToolCatalog(ExecutorService blockingExecutor) {
        requireOpen();
        Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        return ToolCatalog.load(environment, bindings, mapper, blockingExecutor);
    }

    public synchronized ServerDefinition loadServerDefinition(ExecutorService blockingExecutor) {
        requireOpen();
        return new ServerDefinition("mcdev-mcp", AppVersion.current(), ResourceCatalog.INSTRUCTIONS, loadToolCatalog(blockingExecutor), resourceCatalog);
    }

    public synchronized StdioServer startStdio(InputStream input, OutputStream output) {
        requireOpen();
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("MCP server factory can only start one STDIO server");
        }

        var blockingExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            ServerDefinition definition = loadServerDefinition(blockingExecutor);
            return McpSdkAdapter.startStdio(mapper, input, output, definition, blockingExecutor, ownedRuntime);
        } catch (RuntimeException | Error exception) {
            closed.set(true);
            closeExecutorAfterFailure(blockingExecutor, exception);
            closeAfterFailure(ownedRuntime, exception);
            throw exception;
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ownedRuntime.close();
    }

    private void requireOpen() {
        if (closed.get() || ownedRuntime.isClosed()) {
            throw new IllegalStateException("MCP server factory is closed");
        }
    }

    private record DefaultComposition(Map<String, ToolBinding<?>> bindings, ResourceCatalog resourceCatalog, McpJsonMapper mapper, AutoCloseable ownedRuntime) {
    }

    private record RuntimeResources(BridgeSession session, HttpClient client) implements AutoCloseable {
        @Override
        public void close() {
            Throwable failure = null;
            try {
                session.close();
            } catch (Throwable exception) {
                failure = exception;
            }
            try {
                client.close();
            } catch (Throwable exception) {
                if (failure == null) {
                    failure = exception;
                }
                else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    private static final class CloseOnce implements AutoCloseable {
        private final AutoCloseable delegate;
        private final AtomicBoolean closed = new AtomicBoolean();

        private CloseOnce(AutoCloseable delegate) {
            this.delegate = delegate;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    delegate.close();
                } catch (RuntimeException | Error exception) {
                    throw exception;
                } catch (Exception exception) {
                    if (exception instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("Unable to close owned MCP runtime", exception);
                }
            }
        }

        private boolean isClosed() {
            return closed.get();
        }
    }
}