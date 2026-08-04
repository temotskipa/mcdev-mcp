package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeRequest;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.bridge.BridgeTestHarness;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.CompleteToolBindings;
import dev.mcdevmcp.mcp.tool.ToolResult;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CoreRuntimeToolContractTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final AppEnvironment ENVIRONMENT = new AppEnvironment(Map.of());

    private static ToolResult dispatch(ToolCatalog catalog, String tool, Map<String, Object> arguments) throws Exception {
        return catalog.dispatch(tool, arguments, Cancellation.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static CompletableFuture<BridgeResponse> respond(RequestFixture request, BridgeFixture bridge, BridgeRequest wireRequest) {
        String endpoint = wireRequest.endpoint().wireName();
        if (!request.endpoint().equals("status") && endpoint.equals("status")) {
            return CompletableFuture.completedFuture(RuntimeContractFixtures.status(wireRequest.id()));
        }
        if (!request.endpoint().equals(endpoint)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Unexpected endpoint " + endpoint + " for " + request.label()));
        }
        if (bridge.failure() != null) {
            return CompletableFuture.failedFuture(new IllegalStateException(bridge.failure()));
        }
        return CompletableFuture.completedFuture(new BridgeResponse(wireRequest.id(), Boolean.TRUE.equals(bridge.success()), Boolean.TRUE.equals(bridge.resultPresent()), bridge.result(), bridge.output(), bridge.error()));
    }

    private static void assertWireRequest(RequestFixture fixture, List<BridgeRequest> actual) {
        int expectedSize = fixture.endpoint().equals("status") ? 1 : 2;
        assertEquals(expectedSize, actual.size(), fixture.label());
        assertEquals("status", actual.getFirst().endpoint().wireName(), fixture.label());
        assertEquals(Map.of(), actual.getFirst().payload(), fixture.label());
        BridgeRequest target = actual.getLast();
        assertEquals(fixture.endpoint(), target.endpoint().wireName(), fixture.label());
        assertEquals(writeJson(fixture.payload()), writeJson(target.payload()), fixture.label());
    }

    private static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException exception) {
            throw new AssertionError("Unable to serialize contract payload", exception);
        }
    }

    private static List<Duration> expectedEffectiveTimeouts(RequestFixture fixture) {
        Duration targetTimeout = fixture.endpoint().equals("execute") ? Duration.ofMillis(((Number) fixture.payload().get("timeoutMs")).longValue() + 5_000) : Duration.ofSeconds(10);
        return fixture.endpoint().equals("status") ? List.of(Duration.ofSeconds(10)) : List.of(Duration.ofSeconds(10), targetTimeout);
    }

    @Test
    void replaysTheFrozenCoreRuntimeCorpusAndPreservesEveryBridgePayload() throws Exception {
        List<RequestFixture> requests = RuntimeContractFixtures.load(MAPPER, "contracts/runtime-tools/core-requests.jsonl", RequestFixture.class);
        List<BridgeFixture> bridgeResponses = RuntimeContractFixtures.load(MAPPER, "contracts/runtime-tools/core-bridge-responses.jsonl", BridgeFixture.class);
        List<ResultFixture> results = RuntimeContractFixtures.load(MAPPER, "contracts/runtime-tools/core-tool-results.jsonl", ResultFixture.class);
        assertEquals(requests.size(), bridgeResponses.size());
        assertEquals(requests.size(), results.size());

        for (int index = 0; index < requests.size(); index++) {
            RequestFixture request = requests.get(index);
            BridgeFixture bridge = bridgeResponses.get(index);
            ResultFixture expected = results.get(index);
            assertEquals(request.label(), bridge.label(), "bridge fixture " + index);
            assertEquals(request.label(), expected.label(), "result fixture " + index);

            try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, wireRequest) -> respond(request, bridge, wireRequest))) {
                ToolCatalog catalog = ToolCatalog.load(ENVIRONMENT, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER)), MAPPER);
                ToolResult actual = catalog.dispatch(request.tool(), request.arguments(), Cancellation.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);

                assertEquals(expected.text(), actual.content().getFirst().text(), request.label());
                assertEquals(expected.isError(), actual.isError(), request.label());
                assertWireRequest(request, harness.requests());
                assertEquals(expectedEffectiveTimeouts(request), harness.effectiveTimeouts(), request.label());
                int expectedPort = request.arguments().get("port") instanceof Number port ? port.intValue() : 9876;
                assertEquals(List.of(expectedPort), harness.openedPorts(), request.label());
            }
        }
    }

    @Test
    void reconnectsAfterPeerDisconnectionAndResetCreatesANewSession() throws Exception {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (connection, request) -> {
            if (request.endpoint().wireName().equals("status")) {
                return CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
            }
            return CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, Map.of("connection", connection), null, null));
        })) {
            ToolCatalog catalog = ToolCatalog.load(ENVIRONMENT, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER)), MAPPER);

            assertEquals("{\n  \"connection\": 1\n}", dispatch(catalog, "mc_snapshot", Map.of()).content().getFirst().text());
            harness.disconnect();
            assertEquals("{\n  \"connection\": 2\n}", dispatch(catalog, "mc_snapshot", Map.of()).content().getFirst().text());
            ToolResult reset = dispatch(catalog, "mc_connect", Map.of("reset", true));

            assertTrue(reset.content().getFirst().text().startsWith("Connected!\nMinecraft 1.21.11\nPort: 9876"));
            assertEquals(3, harness.connectionCount());
            assertEquals(List.of("status", "snapshot", "status", "snapshot", "status"), harness.requests().stream().map(request -> request.endpoint().wireName()).toList());
        }
    }

    @Test
    void reportsAlreadyConnectedWithoutAnotherBridgeRequest() throws Exception {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, request) -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id())))) {
            ToolCatalog catalog = ToolCatalog.load(ENVIRONMENT, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER)), MAPPER);

            assertTrue(dispatch(catalog, "mc_connect", Map.of()).content().getFirst().text().startsWith("Connected!"));
            ToolResult second = dispatch(catalog, "mc_connect", Map.of());

            assertTrue(second.content().getFirst().text().startsWith("Already connected."));
            assertTrue(second.content().getFirst().text().endsWith("Use reset=true to reconnect."));
            assertEquals(1, harness.requests().size());
        }
    }

    @Test
    void exposesAllCoreRuntimeBindingsInOrderAndRejectsInvalidArgumentsBeforeSending() throws Exception {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, request) -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id())))) {
            Map<String, ?> handlers = RuntimeToolModule.handlers(harness.session(), MAPPER);
            List<String> coreNames = List.of("mc_connect", "mc_execute", "mc_snapshot", "mc_nearby_entities", "mc_entity_details", "mc_nearby_blocks", "mc_block_details", "mc_looked_at_entity", "mc_chat_history", "mc_screen_inspect");
            assertEquals(coreNames, List.copyOf(handlers.keySet()).subList(0, coreNames.size()));
            assertDoesNotThrow(handlers::clear);

            ToolCatalog catalog = ToolCatalog.load(ENVIRONMENT, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER)), MAPPER);
            ToolResult timeout = dispatch(catalog, "mc_execute", Map.of("code", "return 1", "timeoutMs", 999));
            ToolResult port = dispatch(catalog, "mc_connect", Map.of("port", 1.5));

            assertTrue(timeout.isError());
            assertEquals("Error executing mc_execute: 'timeoutMs' must be an integer from 1000 to 300000", timeout.content().getFirst().text());
            assertTrue(port.isError());
            assertEquals("Error executing mc_connect: 'port' must be an integer from 1 to 65535", port.content().getFirst().text());
            assertTrue(harness.requests().isEmpty());
        }
    }

    private record RequestFixture(String label, String tool, Map<String, Object> arguments, String endpoint, Map<String, Object> payload) {
    }

    private record BridgeFixture(String label, Boolean success, Boolean resultPresent, Object result, String output, String error, String failure) {
    }

    private record ResultFixture(String label, String text, boolean isError) {
    }
}
