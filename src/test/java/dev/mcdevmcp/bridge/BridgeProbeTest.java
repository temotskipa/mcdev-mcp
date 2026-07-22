package dev.mcdevmcp.bridge;

import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BridgeProbeTest {
    @Test
    void exposesOnlySessionStateAndCanProbeStatusWithoutMutation() {
        FakeDebugBridge bridge = new FakeDebugBridge(Map.of());
        BridgeSession session = new BridgeSession(new BridgeJson(McpJsonDefaults.getMapper()), new AppEnvironment(Map.of()), _ -> CompletableFuture.completedFuture(bridge.client()));
        BridgeProbe probe = new BridgeProbe(session);

        assertFalse(probe.connectedPort().isPresent());
        assertTrue(probe.status().toCompletableFuture().join().success());
        assertEquals(9876, probe.connectedPort().orElseThrow());
        assertEquals("1.21.11", probe.sessionInfo().orElseThrow().version().value());
        session.close();
    }
}
