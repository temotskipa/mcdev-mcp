package dev.mcdevmcp.bridge;

import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BridgeClientTest {
    @Test
    void correlatesConcurrentResponsesWithoutCrossCompletionAndIgnoresLateMessages() {
        ConcurrentHashMap<String, CompletableFuture<BridgeResponse>> responses = new ConcurrentHashMap<>();
        BridgeClient client = BridgeClient.testing(new BridgeJson(McpJsonDefaults.getMapper()), request -> {
            CompletableFuture<BridgeResponse> response = new CompletableFuture<>();
            responses.put(request.id(), response);
            return response;
        });

        CompletableFuture<BridgeResponse> first = client.send(new BridgeEndpoint("one"), null, Duration.ofSeconds(1)).toCompletableFuture();
        CompletableFuture<BridgeResponse> second = client.send(new BridgeEndpoint("two"), null, Duration.ofSeconds(1)).toCompletableFuture();
        responses.get("req_2").complete(new BridgeResponse("req_2", true, "second", "", null));
        responses.get("req_1").complete(new BridgeResponse("req_1", true, "first", "", null));

        assertEquals("first", first.join().result());
        assertEquals("second", second.join().result());
        client.receiveMessage("{\"id\":\"req_99\",\"success\":true,\"result\":null}");
        assertEquals(0, client.pendingRequestCount());
        client.close();
    }

    @Test
    void closeRejectsOutstandingCallsAndCapsEndpointTimeouts() {
        CompletableFuture<BridgeResponse> delayed = new CompletableFuture<>();
        BridgeClient client = BridgeClient.testing(new BridgeJson(McpJsonDefaults.getMapper()), ignored -> delayed);
        CompletableFuture<BridgeResponse> call = client.send(new BridgeEndpoint("status"), null, Duration.ofDays(1)).toCompletableFuture();

        assertEquals(Duration.ofSeconds(10), BridgeClient.effectiveTimeout(null));
        assertEquals(Duration.ofMinutes(5), BridgeClient.effectiveTimeout(Duration.ofDays(1)));
        client.close();

        assertThrows(Exception.class, call::join);
        assertFalse(client.pendingRequestCount() > 0);
    }

    @Test
    void parallelSendsKeepEachPublishedRequestAttachedToItsOwnPayload() {
        BridgeClient client = BridgeClient.testing(new BridgeJson(McpJsonDefaults.getMapper()), request -> CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, request.endpoint().wireName(), "", null)));

        try (var executor = Executors.newFixedThreadPool(8)) {
            List<CompletableFuture<BridgeResponse>> calls = IntStream.range(0, 128).mapToObj(index -> CompletableFuture.supplyAsync(() -> client.send(new BridgeEndpoint("endpoint-" + index), index, Duration.ofSeconds(1)).toCompletableFuture().join(), executor)).toList();

            for (int index = 0; index < calls.size(); index++) {
                assertEquals("endpoint-" + index, calls.get(index).join().result());
            }
        }
        assertEquals(0, client.pendingRequestCount());
        client.close();
    }

    @Test
    void invalidTimeoutIsRejectedBeforePublishingARequest() {
        BridgeClient client = BridgeClient.testing(new BridgeJson(McpJsonDefaults.getMapper()), request -> CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, null, "", null)));

        assertThrows(IllegalArgumentException.class, () -> client.send(new BridgeEndpoint("status"), null, Duration.ZERO));
        assertEquals(0, client.pendingRequestCount());
        client.close();
    }
}
