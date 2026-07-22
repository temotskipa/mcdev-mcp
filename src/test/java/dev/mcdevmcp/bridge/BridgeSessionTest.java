package dev.mcdevmcp.bridge;

import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BridgeSessionTest {
    private static final BridgeJson JSON = new BridgeJson(McpJsonDefaults.getMapper());

    @Test
    void coalescesImplicitConnectionsScansPortsAndResets() {
        AtomicInteger attempts = new AtomicInteger();
        FakeDebugBridge bridge = new FakeDebugBridge(Map.of());
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of("DEBUGBRIDGE_PORT", "invalid")), port -> {
            attempts.incrementAndGet();
            return port == 9878 ? CompletableFuture.completedFuture(bridge.client()) : CompletableFuture.failedFuture(new IllegalStateException("not listening"));
        });

        CompletableFuture<SessionInfo> first = session.connect(null).toCompletableFuture();
        CompletableFuture<SessionInfo> second = session.connect(null).toCompletableFuture();

        assertEquals(9878, first.join().port());
        assertEquals(0L, first.join().refs());
        assertTrue(first.join().gameDir().orElseThrow().isAbsolute());
        assertEquals(first.join(), second.join());
        assertEquals(3, attempts.get());
        assertEquals(9878, session.connectedPort().orElseThrow());
        session.reset();
        assertFalse(session.connectedPort().isPresent());
        session.close();
    }

    @Test
    void explicitAdoptionRemainsDeliberateAndSendUsesTheConnectedClient() {
        FakeDebugBridge bridge = new FakeDebugBridge(Map.of());
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> CompletableFuture.completedFuture(bridge.client()));

        assertEquals(9999, session.adoptPort(9999).toCompletableFuture().join().port());
        BridgeResponse response = session.send(new BridgeEndpoint("echo"), Map.of("value", 7), Duration.ofMillis(1)).toCompletableFuture().join();

        assertTrue(response.success());
        assertEquals("req_2", response.id());
        assertEquals(Duration.ofSeconds(5).plusMillis(1), BridgeClient.effectiveTimeout(Duration.ofMillis(1)));
        session.close();
    }

    @Test
    void explicitConnectPinsItsPortUntilReset() {
        List<Integer> openedPorts = new ArrayList<>();
        List<BridgeClient> openedClients = new ArrayList<>();
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), port -> {
            openedPorts.add(port);
            BridgeClient client = FakeDebugBridge.client(JSON, "1.21.11");
            openedClients.add(client);
            return CompletableFuture.completedFuture(client);
        });

        assertEquals(9999, session.connect(9999).toCompletableFuture().join().port());
        openedClients.getLast().peerClosed(new IllegalStateException("gone"));
        assertEquals(9999, session.connect(null).toCompletableFuture().join().port());
        session.reset();
        assertEquals(9876, session.connect(null).toCompletableFuture().join().port());

        assertEquals(List.of(9999, 9999, 9876), openedPorts);
        session.close();
    }

    @Test
    void adoptedPortDoesNotDisableLaterAutoScan() {
        List<Integer> openedPorts = new ArrayList<>();
        List<BridgeClient> openedClients = new ArrayList<>();
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), port -> {
            openedPorts.add(port);
            BridgeClient client = FakeDebugBridge.client(JSON, "1.21.11");
            openedClients.add(client);
            return CompletableFuture.completedFuture(client);
        });

        assertEquals(9876, session.connect(null).toCompletableFuture().join().port());
        assertEquals(9999, session.adoptPort(9999).toCompletableFuture().join().port());
        openedClients.getLast().peerClosed(new IllegalStateException("gone"));
        assertEquals(9876, session.connect(null).toCompletableFuture().join().port());

        assertEquals(List.of(9876, 9999, 9876), openedPorts);
        session.close();
    }

    @Test
    void implicitReconnectToAPinnedPortIsShared() {
        BridgeClient firstClient = FakeDebugBridge.client(JSON, "first");
        CompletableFuture<BridgeClient> delayedReconnect = new CompletableFuture<>();
        AtomicInteger opens = new AtomicInteger();
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> opens.getAndIncrement() == 0 ? CompletableFuture.completedFuture(firstClient) : delayedReconnect);
        assertEquals(9999, session.connect(9999).toCompletableFuture().join().port());
        firstClient.peerClosed(new IllegalStateException("gone"));

        CompletableFuture<SessionInfo> firstReconnect = session.connect(null).toCompletableFuture();
        CompletableFuture<SessionInfo> secondReconnect = session.connect(null).toCompletableFuture();
        assertSame(firstReconnect, secondReconnect);
        delayedReconnect.complete(FakeDebugBridge.client(JSON, "second"));

        assertEquals(9999, firstReconnect.join().port());
        assertEquals(2, opens.get());
        session.close();
    }

    @Test
    void environmentPortAcceptsNodeStyleWhitespaceAndIntegralDecimalText() {
        List<Integer> openedPorts = new ArrayList<>();
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of("DEBUGBRIDGE_PORT", " 9999.0 ")), port -> {
            openedPorts.add(port);
            return CompletableFuture.completedFuture(FakeDebugBridge.client(JSON, "1.21.11"));
        });

        assertEquals(9999, session.connect(null).toCompletableFuture().join().port());
        assertEquals(List.of(9999), openedPorts);
        session.close();
    }
}
