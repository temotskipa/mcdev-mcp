package dev.mcdevmcp.bridge;

import dev.mcdevmcp.bridge.payload.EmptyBridgePayload;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class BridgeSession implements AutoCloseable {
    private static final int DEFAULT_PORT = 9876;
    private static final int PORTS_TO_SCAN = 11;
    private static final BridgeEndpoint STATUS = new BridgeEndpoint("status");

    private final AppEnvironment environment;
    private final Connector connector;
    private final Consumer<String> diagnostics;
    private final BridgeResultDecoder resultDecoder;
    private final Set<CompletableFuture<SessionInfo>> connectionAttempts = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<CompletableFuture<BridgeClient>> inFlightOpenings = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BridgeClient> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
    private CompletableFuture<SessionInfo> implicitConnect;
    private Connected connected;
    private SessionInfo lastSessionInfo;
    private Integer configuredPort;
    private long generation;
    private boolean closed;

    public BridgeSession() {
        this(new BridgeJson(McpJsonDefaults.getMapper()), AppEnvironment.system(), defaultConnector(new BridgeJson(McpJsonDefaults.getMapper())), ignored -> {
        });
    }

    public BridgeSession(HttpClient client, McpJsonMapper mapper, AppEnvironment environment, Consumer<String> diagnostics) {
        this(new BridgeJson(Objects.requireNonNull(mapper, "mapper")), environment, defaultConnector(Objects.requireNonNull(client, "client"), new BridgeJson(mapper)), diagnostics);
    }

    BridgeSession(BridgeJson json, AppEnvironment environment, Connector connector) {
        this(json, environment, connector, ignored -> {
        });
    }

    BridgeSession(BridgeJson json, AppEnvironment environment, Connector connector, Consumer<String> diagnostics) {
        Objects.requireNonNull(json, "json");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.resultDecoder = new BridgeResultDecoder(json.mapper());
    }

    private static void closeQuietly(BridgeClient client) {
        if (client != null) {
            client.close();
        }
    }

    private static Connector defaultConnector(BridgeJson json) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return defaultConnector(client, json);
    }

    private static Connector defaultConnector(HttpClient client, BridgeJson json) {
        return port -> BridgeClient.connect(client, URI.create("ws://127.0.0.1:" + port), json);
    }

    private static int requireExplicitPort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("DebugBridge port must be in range: " + port);
        }
        return port;
    }

    private static SessionInfo toSessionInfo(int port, BridgeStatusWire status) {
        if (status.version() == null || status.mappingStatus() == null || status.obfuscated() == null || status.refs() == null) {
            throw new IllegalArgumentException("DebugBridge status response is missing required fields");
        }
        return new SessionInfo(port, new MinecraftVersion(status.version()), BridgeMappingStatus.fromWire(status.mappingStatus()), status.obfuscated(), status.refs(), path(status.gameDir()), path(status.logsDir()), path(status.latestLog()), Optional.ofNullable(status.latestLogExists()), path(status.debugLog()), Optional.ofNullable(status.debugLogExists()), Optional.ofNullable(status.sessionControlEnabled()));
    }

    private static Optional<Path> path(String value) {
        return value == null ? Optional.empty() : Optional.of(Path.of(value));
    }

    private static boolean identityChanged(SessionInfo previous, SessionInfo next) {
        return previous.gameDir().isPresent() && next.gameDir().isPresent() ? !previous.gameDir().equals(next.gameDir()) : !previous.version().equals(next.version());
    }

    private static String display(SessionInfo info) {
        return "port " + info.port() + ", game " + BridgePayloadValidator.safeDisplay(info.gameDir().map(Path::toString).orElse(info.version().value()));
    }

    public synchronized CompletionStage<SessionInfo> connect(Integer explicitPort) {
        ensureOpen();
        if (explicitPort != null) {
            int port = requireExplicitPort(explicitPort);
            supersede();
            configuredPort = port;
            long token = generation;
            CompletionStage<BridgeClient> opening = openCandidate(port);
            return startConnect(() -> connectPort(token, port, opening));
        }
        if (connected != null) {
            return CompletableFuture.completedFuture(connected.info());
        }
        if (implicitConnect != null) {
            return implicitConnect;
        }
        long token = generation;
        Integer pinned = configuredPort;
        AsyncAttempt<SessionInfo> started;
        if (pinned == null) {
            int base = basePort();
            CompletionStage<BridgeClient> firstOpening = openCandidate(base);
            started = newAttempt(() -> scanPorts(token, base, firstOpening));
        }
        else {
            CompletionStage<BridgeClient> opening = openCandidate(pinned);
            started = newAttempt(() -> connectPort(token, pinned, opening));
        }
        implicitConnect = started;
        return started.start();
    }

    public synchronized CompletionStage<SessionInfo> adoptPort(int port) {
        ensureOpen();
        int explicit = requireExplicitPort(port);
        Integer preservedConfiguredPort = configuredPort;
        disconnect();
        configuredPort = preservedConfiguredPort;
        long token = generation;
        CompletionStage<BridgeClient> opening = openCandidate(explicit);
        return startConnect(() -> connectPort(token, explicit, opening));
    }

    public CompletionStage<BridgeResponse> send(BridgeEndpoint endpoint, BridgePayload payload, Duration endpointTimeout) {
        Objects.requireNonNull(endpoint, "endpoint");
        return new AsyncAttempt<>(() -> {
            SessionInfo connectedInfo = awaitWithoutCancel(connect(null));
            Objects.requireNonNull(connectedInfo, "DebugBridge connect returned no session");
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("DebugBridge send cancelled");
            }
            BridgeClient client;
            synchronized (this) {
                if (connected == null) {
                    throw new IllegalStateException("DebugBridge session is disconnected");
                }
                client = connected.client();
            }
            return await(client.send(endpoint, payload, endpointTimeout));
        }).start();
    }

    public synchronized OptionalInt connectedPort() {
        return connected == null ? OptionalInt.empty() : OptionalInt.of(connected.info().port());
    }

    public synchronized Optional<SessionInfo> sessionInfo() {
        return Optional.ofNullable(lastSessionInfo);
    }

    @SuppressWarnings("preview")
    public CompletionStage<SessionInfo> probe(int port) {
        int explicit = requireExplicitPort(port);
        return new AsyncAttempt<>(() -> {
            try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow(), config -> config.withName("debugbridge-probe").withTimeout(Duration.ofMillis(1_500)))) {
                var subtask = scope.fork(() -> probePort(explicit));
                scope.join();
                return subtask.get();
            }
        }).start();
    }

    public synchronized void disconnect() {
        generation++;
        implicitConnect = null;
        Set<CompletableFuture<SessionInfo>> pendingAttempts = Set.copyOf(connectionAttempts);
        connectionAttempts.clear();
        Set<BridgeClient> pendingCandidates = Set.copyOf(candidates);
        candidates.clear();
        Connected previous = connected;
        connected = null;
        CancellationException cancellation = new CancellationException("DebugBridge session disconnected");
        pendingAttempts.forEach(attempt -> attempt.completeExceptionally(cancellation));
        Set<CompletableFuture<BridgeClient>> pendingOpenings = Set.copyOf(inFlightOpenings);
        inFlightOpenings.clear();
        pendingOpenings.forEach(opening -> opening.whenComplete((client, _) -> closeQuietly(client)));
        pendingCandidates.forEach(BridgeClient::close);
        if (previous != null) {
            previous.client().close();
        }
    }

    public synchronized void reset() {
        disconnect();
        lastSessionInfo = null;
        configuredPort = null;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        reset();
    }

    private SessionInfo scanPorts(long token, int base, CompletionStage<BridgeClient> firstOpening) throws Exception {
        for (int offset = 0; offset < PORTS_TO_SCAN; offset++) {
            if (stale(token)) {
                throw new CancellationException("DebugBridge session changed during scan");
            }
            int port = base + offset;
            try {
                CompletionStage<BridgeClient> opening = offset == 0 ? firstOpening : openCandidate(port);
                return connectPort(token, port, opening);
            } catch (CancellationException | InterruptedException exception) {
                throw exception;
            } catch (Exception ignored) {
                // Try the next port in the documented scan window.
            }
        }
        throw new IllegalStateException("No DebugBridge instance accepted status on ports " + base + "-" + (base + PORTS_TO_SCAN - 1));
    }

    private SessionInfo connectPort(long token, int port, CompletionStage<BridgeClient> opening) throws Exception {
        BridgeClient client = awaitOpening(opening);
        boolean adopted = false;
        try {
            if (stale(token) || candidateRejected(token, client)) {
                throw new CancellationException("DebugBridge session changed during connect");
            }
            client.onClosed(this::clearDeadClient);
            BridgeResponse response = await(client.send(STATUS, new EmptyBridgePayload(), null));
            SessionInfo info = acceptStatus(token, port, client, response);
            adopted = true;
            return info;
        } finally {
            if (!adopted) {
                releaseCandidate(client);
                closeQuietly(client);
            }
        }
    }

    private SessionInfo probePort(int port) throws Exception {
        BridgeClient client = awaitOpening(openCandidate(port));
        try {
            BridgeResponse response = await(client.send(STATUS, new EmptyBridgePayload(), Duration.ofMillis(1_500)));
            BridgeStatusWire wire = resultDecoder.decode(STATUS, BridgePayloadValidator.requireResult("status", response), BridgeResultTypes.STATUS);
            return toSessionInfo(port, wire);
        } finally {
            closeQuietly(client);
        }
    }

    @SuppressWarnings("resource")
    private SessionInfo acceptStatus(long token, int port, BridgeClient client, BridgeResponse response) {
        BridgeStatusWire status = resultDecoder.decode(STATUS, BridgePayloadValidator.requireResult("status", response), BridgeResultTypes.STATUS);
        SessionInfo info = toSessionInfo(port, status);
        synchronized (this) {
            if (stale(token) || client.isClosed()) {
                candidates.remove(client);
                client.close();
                throw new CancellationException("DebugBridge session changed or closed during status");
            }
            Connected previous = connected;
            if (lastSessionInfo != null && identityChanged(lastSessionInfo, info)) {
                diagnostics.accept("DebugBridge session identity changed from " + display(lastSessionInfo) + " to " + display(info));
            }
            if (previous != null && previous.client() != client) {
                previous.client().close();
            }
            candidates.remove(client);
            connected = new Connected(client, info);
            lastSessionInfo = info;
            return info;
        }
    }

    private synchronized boolean stale(long token) {
        return closed || generation != token;
    }

    private synchronized void clearImplicit(CompletableFuture<SessionInfo> result) {
        if (implicitConnect == result) {
            implicitConnect = null;
        }
    }

    @SuppressWarnings("resource")
    private synchronized void clearDeadClient(BridgeClient client) {
        candidates.remove(client);
        if (connected != null && connected.client() == client) {
            connected = null;
        }
    }

    private synchronized CompletionStage<SessionInfo> startConnect(Callable<SessionInfo> work) {
        return newAttempt(work).start();
    }

    private synchronized AsyncAttempt<SessionInfo> newAttempt(Callable<SessionInfo> work) {
        AsyncAttempt<SessionInfo> attempt = new AsyncAttempt<>(work);
        connectionAttempts.add(attempt);
        attempt.whenComplete((_, _) -> {
            removeAttempt(attempt);
            clearImplicit(attempt);
        });
        return attempt;
    }

    private synchronized void removeAttempt(CompletableFuture<SessionInfo> attempt) {
        connectionAttempts.remove(attempt);
    }

    private synchronized boolean candidateRejected(long token, BridgeClient client) {
        if (closed || generation != token) {
            return true;
        }
        candidates.add(client);
        return false;
    }

    private synchronized void releaseCandidate(BridgeClient client) {
        candidates.remove(client);
    }

    private CompletionStage<BridgeClient> openCandidate(int port) {
        try {
            CompletionStage<BridgeClient> opened = connector.open(port);
            if (opened == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("DebugBridge connector returned no stage for port " + port));
            }
            CompletableFuture<BridgeClient> future = opened.toCompletableFuture();
            synchronized (this) {
                inFlightOpenings.add(future);
            }
            future.whenComplete((_, _) -> {
                synchronized (this) {
                    inFlightOpenings.remove(future);
                }
            });
            return future;
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private int basePort() {
        return environment.debugBridgePort().orElse(DEFAULT_PORT);
    }

    private void supersede() {
        reset();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("DebugBridge session is closed");
        }
    }

    @FunctionalInterface
    interface Connector {
        CompletionStage<BridgeClient> open(int port);
    }

    private record Connected(BridgeClient client, SessionInfo info) {
    }

    private static <T> T await(CompletionStage<T> stage) throws InterruptedException, ExecutionException {
        CompletableFuture<T> future = stage.toCompletableFuture();
        try {
            return future.get();
        } catch (InterruptedException exception) {
            future.cancel(true);
            throw exception;
        }
    }

    private BridgeClient awaitOpening(CompletionStage<BridgeClient> stage) throws InterruptedException, ExecutionException {
        CompletableFuture<BridgeClient> future = stage.toCompletableFuture();
        synchronized (this) {
            inFlightOpenings.add(future);
        }
        try {
            return future.get();
        } catch (InterruptedException exception) {
            future.whenComplete((client, _) -> closeQuietly(client));
            throw exception;
        } finally {
            synchronized (this) {
                inFlightOpenings.remove(future);
            }
        }
    }

    private static <T> T awaitWithoutCancel(CompletionStage<T> stage) throws InterruptedException, ExecutionException {
        return stage.toCompletableFuture().get();
    }

    @SuppressWarnings("preview")
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException || current instanceof StructuredTaskScope.FailedException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * The unstructured CompletionStage boundary. Work inside the owner is sequential
     * and interruptible; send cancellation does not cancel a shared connect.
     */
    private static final class AsyncAttempt<T> extends CompletableFuture<T> {
        private final Thread worker;

        private AsyncAttempt(Callable<T> work) {
            worker = Thread.ofVirtual().name("debugbridge-session").unstarted(() -> {
                try {
                    super.complete(work.call());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    super.cancel(false);
                } catch (CancellationException exception) {
                    super.cancel(false);
                } catch (ExecutionException exception) {
                    super.completeExceptionally(unwrap(exception.getCause() == null ? exception : exception.getCause()));
                } catch (Throwable exception) {
                    super.completeExceptionally(unwrap(exception));
                }
            });
        }

        private AsyncAttempt<T> start() {
            worker.start();
            return this;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                worker.interrupt();
            }
            return cancelled;
        }

        @Override
        public boolean completeExceptionally(Throwable ex) {
            boolean completed = super.completeExceptionally(ex);
            if (completed) {
                worker.interrupt();
            }
            return completed;
        }
    }
}