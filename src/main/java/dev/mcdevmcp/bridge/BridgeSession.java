package dev.mcdevmcp.bridge;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public final class BridgeSession implements AutoCloseable {
    private static final int DEFAULT_PORT = 9876;
    private static final int PORTS_TO_SCAN = 11;
    private static final BridgeEndpoint STATUS = new BridgeEndpoint("status");

    private final AppEnvironment environment;
    private final Connector connector;
    private final Consumer<String> diagnostics;
    private final BridgePayloadValidator payloadValidator;
    private final Set<CompletableFuture<SessionInfo>> connectionAttempts = Collections.newSetFromMap(new IdentityHashMap<>());
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
        this.payloadValidator = new BridgePayloadValidator(json.mapper());
    }

    public synchronized CompletionStage<SessionInfo> connect(Integer explicitPort) {
        ensureOpen();
        if (explicitPort != null) {
            int port = requireExplicitPort(explicitPort);
            supersede();
            configuredPort = port;
            return openPort(port, generation);
        }
        if (connected != null) {
            return CompletableFuture.completedFuture(connected.info());
        }
        if (implicitConnect != null) {
            return implicitConnect;
        }
        CompletableFuture<SessionInfo> started = newAttempt();
        implicitConnect = started;
        if (configuredPort == null) {
            scanPort(generation, basePort(), 0, started);
        }
        else {
            openPort(configuredPort, generation).whenComplete((info, failure) -> {
                if (failure == null) {
                    started.complete(info);
                }
                else {
                    started.completeExceptionally(failure);
                }
                clearImplicit(started);
            });
        }
        return started;
    }

    public synchronized CompletionStage<SessionInfo> adoptPort(int port) {
        ensureOpen();
        int explicit = requireExplicitPort(port);
        Integer preservedConfiguredPort = configuredPort;
        supersede();
        configuredPort = preservedConfiguredPort;
        return openPort(explicit, generation);
    }

    @SuppressWarnings("resource")
    public CompletionStage<BridgeResponse> send(BridgeEndpoint endpoint, Object payload, Duration endpointTimeout) {
        Objects.requireNonNull(endpoint, "endpoint");
        return connect(null).thenCompose(ignored -> {
            synchronized (this) {
                if (connected == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("DebugBridge session is disconnected"));
                }
                return connected.client().send(endpoint, payload, endpointTimeout);
            }
        });
    }

    public synchronized OptionalInt connectedPort() {
        return connected == null ? OptionalInt.empty() : OptionalInt.of(connected.info().port());
    }

    public synchronized Optional<SessionInfo> sessionInfo() {
        return Optional.ofNullable(lastSessionInfo);
    }

    public synchronized void reset() {
        generation++;
        implicitConnect = null;
        Set<CompletableFuture<SessionInfo>> pendingAttempts = Set.copyOf(connectionAttempts);
        connectionAttempts.clear();
        Set<BridgeClient> pendingCandidates = Set.copyOf(candidates);
        candidates.clear();
        Connected previous = connected;
        connected = null;
        lastSessionInfo = null;
        CancellationException cancellation = new CancellationException("DebugBridge session reset");
        pendingAttempts.forEach(attempt -> attempt.completeExceptionally(cancellation));
        pendingCandidates.forEach(BridgeClient::close);
        if (previous != null) {
            previous.client().close();
        }
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

    private void scanPort(long token, int port, int offset, CompletableFuture<SessionInfo> result) {
        if (stale(token)) {
            result.completeExceptionally(new CancellationException("DebugBridge session changed during scan"));
            return;
        }
        if (offset >= PORTS_TO_SCAN) {
            result.completeExceptionally(new IllegalStateException("No DebugBridge instance accepted status on ports " + basePort() + "-" + (basePort() + PORTS_TO_SCAN - 1)));
            clearImplicit(result);
            return;
        }
        openCandidate(port).whenComplete((client, failure) -> {
            if (stale(token)) {
                closeQuietly(client);
                result.completeExceptionally(new CancellationException("DebugBridge session changed during scan"));
                return;
            }
            if (failure != null || client == null) {
                scanPort(token, port + 1, offset + 1, result);
                return;
            }
            if (candidateRejected(token, client)) {
                closeQuietly(client);
                result.completeExceptionally(new CancellationException("DebugBridge session changed during scan"));
                return;
            }
            verifyStatus(token, port, client).whenComplete((info, statusFailure) -> {
                if (statusFailure == null) {
                    result.complete(info);
                    clearImplicit(result);
                }
                else {
                    releaseCandidate(client);
                    closeQuietly(client);
                    scanPort(token, port + 1, offset + 1, result);
                }
            });
        });
    }

    private CompletionStage<SessionInfo> openPort(int port, long token) {
        CompletableFuture<SessionInfo> result = newAttempt();
        openCandidate(port).whenComplete((client, failure) -> {
            if (failure != null || client == null) {
                result.completeExceptionally(failure == null ? new IllegalStateException("DebugBridge port " + port + " did not open") : failure);
                return;
            }
            if (stale(token)) {
                closeQuietly(client);
                result.completeExceptionally(new CancellationException("DebugBridge session changed during connect"));
                return;
            }
            if (candidateRejected(token, client)) {
                closeQuietly(client);
                result.completeExceptionally(new CancellationException("DebugBridge session changed during connect"));
                return;
            }
            verifyStatus(token, port, client).whenComplete((info, statusFailure) -> {
                if (statusFailure == null) {
                    result.complete(info);
                }
                else {
                    releaseCandidate(client);
                    closeQuietly(client);
                    result.completeExceptionally(statusFailure);
                }
            });
        });
        return result;
    }

    private CompletionStage<SessionInfo> verifyStatus(long token, int port, BridgeClient client) {
        client.onClosed(this::clearDeadClient);
        return client.send(STATUS, Map.of(), null).thenApply(response -> acceptStatus(token, port, client, response));
    }

    @SuppressWarnings("resource")
    private SessionInfo acceptStatus(long token, int port, BridgeClient client, BridgeResponse response) {
        BridgeStatusWire status = payloadValidator.requireResult("status", response, BridgeStatusWire.class);
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

    private synchronized CompletableFuture<SessionInfo> newAttempt() {
        CompletableFuture<SessionInfo> attempt = new CompletableFuture<>();
        connectionAttempts.add(attempt);
        attempt.whenComplete((_, _) -> removeAttempt(attempt));
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

    private CompletionStage<BridgeClient> openCandidate(int port) {
        try {
            CompletionStage<BridgeClient> opened = connector.open(port);
            return opened == null ? CompletableFuture.failedFuture(new IllegalStateException("DebugBridge connector returned no stage for port " + port)) : opened;
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private int basePort() {
        return environment.value("DEBUGBRIDGE_PORT").flatMap(BridgeSession::parsePort).orElse(DEFAULT_PORT);
    }

    private static Optional<Integer> parsePort(String text) {
        try {
            double numeric = Double.parseDouble(text.strip());
            if (!Double.isFinite(numeric) || numeric != Math.rint(numeric) || numeric < 1 || numeric > 65535) {
                return Optional.empty();
            }
            return Optional.of((int) numeric);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static int requireExplicitPort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("DebugBridge port must be in range: " + port);
        }
        return port;
    }

    private void supersede() {
        reset();
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
}
