package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeRequest;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.bridge.BridgeTestHarness;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.ToolResult;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SessionRuntimeToolContractTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final AppEnvironment DEFAULT_ENVIRONMENT = new AppEnvironment(Map.of());

    @Test
    void replaysTheFrozenSessionCorpusWithExactPayloadsTimeoutsAndText() throws Exception {
        List<RequestFixture> requests = RuntimeContractFixtures.load(MAPPER, "contracts/runtime-tools/session-requests.jsonl", RequestFixture.class);
        List<BridgeFixture> bridgeResponses = RuntimeContractFixtures.load(MAPPER, "contracts/runtime-tools/session-bridge-responses.jsonl", BridgeFixture.class);
        List<ResultFixture> results = RuntimeContractFixtures.load(MAPPER, "contracts/runtime-tools/session-tool-results.jsonl", ResultFixture.class);
        assertEquals(requests.size(), bridgeResponses.size());
        assertEquals(requests.size(), results.size());

        for (int index = 0; index < requests.size(); index++) {
            RequestFixture request = requests.get(index);
            BridgeFixture bridge = bridgeResponses.get(index);
            ResultFixture expected = results.get(index);
            assertEquals(request.label(), bridge.label());
            assertEquals(request.label(), expected.label());

            AppEnvironment environment = request.tool().equals("mc_run_command") ? new AppEnvironment(Map.of("MCDEV_RUN_COMMAND", "1")) : DEFAULT_ENVIRONMENT;
            try (var harness = new BridgeTestHarness(MAPPER, environment, (_, wireRequest) -> respond(request, bridge, wireRequest))) {
                ToolCatalog catalog = ToolCatalog.load(environment, RuntimeToolModule.handlers(harness.session(), MAPPER, environment), MAPPER);
                ToolResult actual = dispatch(catalog, request.tool(), request.arguments());

                assertEquals(expected.text(), actual.content().getFirst().text(), request.label());
                assertEquals(expected.isError(), actual.isError(), request.label());
                assertWireRequest(request, harness.requests());
                Duration targetTimeout = request.endpoint().equals("joinServer") ? Duration.ofSeconds(70) : Duration.ofSeconds(10);
                assertEquals(List.of(Duration.ofSeconds(10), targetTimeout), harness.effectiveTimeouts(), request.label());
            }
        }
    }

    @Test
    void rendersImmediateJoinedAndDisconnectedPollOutcomes() throws Exception {
        try (var joined = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, request) -> switch (request.endpoint().wireName()) {
            case "status" -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
            case "snapshot" -> CompletableFuture.completedFuture(success(request, Map.of("player", Map.of("x", 0))));
            default ->
                    CompletableFuture.failedFuture(new AssertionError("Unexpected endpoint: " + request.endpoint().wireName()));
        })) {
            ToolResult result = dispatch(ToolCatalog.load(DEFAULT_ENVIRONMENT, RuntimeToolModule.handlers(joined.session(), MAPPER), MAPPER), "mc_wait_until_in_world", Map.of());
            assertEquals("In-world after 0s.", result.content().getFirst().text());
            assertFalse(result.isError());
        }

        try (var disconnected = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, request) -> switch (request.endpoint().wireName()) {
            case "status" -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
            case "snapshot" -> CompletableFuture.completedFuture(success(request, Map.of()));
            case "screenInspect" ->
                    CompletableFuture.completedFuture(success(request, Map.of("type", "net.minecraft.client.gui.screens.DisconnectedScreen", "title", "Connection refused")));
            default ->
                    CompletableFuture.failedFuture(new AssertionError("Unexpected endpoint: " + request.endpoint().wireName()));
        })) {
            ToolResult result = dispatch(ToolCatalog.load(DEFAULT_ENVIRONMENT, RuntimeToolModule.handlers(disconnected.session(), MAPPER), MAPPER), "mc_wait_until_in_world", Map.of());
            assertEquals("Join failed — DisconnectedScreen shown.\nReason: Connection refused", result.content().getFirst().text());
            assertTrue(result.isError());
        }
    }

    @Test
    void rejectsDisabledSessionControlBeforeCallingAGatedEndpoint() throws Exception {
        try (var harness = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, request) -> CompletableFuture.completedFuture(disabledStatus(request)))) {
            ToolCatalog catalog = ToolCatalog.load(DEFAULT_ENVIRONMENT, RuntimeToolModule.handlers(harness.session(), MAPPER), MAPPER);

            ToolResult result = dispatch(catalog, "mc_join_server", Map.of("address", "localhost", "wait", false));

            assertTrue(result.isError());
            assertEquals("Session control is disabled in DebugBridge (session_control_enabled=false, the default).\nTo enable it: edit C:\\Game\\config\\debugbridge.json, set \"session_control_enabled\": true, then restart the Minecraft client — the flag is only read at startup.", result.content().getFirst().text());
            assertEquals(List.of("status"), harness.requests().stream().map(request -> request.endpoint().wireName()).toList());
        }
    }

    @Test
    void reconnectsToTheFirstMatchingBridgeAndQueuesQuitWithoutWaiting() throws Exception {
        try (var bridge = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, request) -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id())))) {
            ToolResult result = dispatch(ToolCatalog.load(DEFAULT_ENVIRONMENT, RuntimeToolModule.handlers(bridge.session(), MAPPER), MAPPER), "mc_wait_for_bridge", Map.of());
            assertEquals("Connected: Minecraft 1.21.11 on port 9876.\nGame dir: C:\\Game\nSession control: enabled", result.content().getFirst().text());
            assertEquals(List.of(9876, 9876), bridge.openedPorts());
            assertEquals(List.of("status", "status"), bridge.requests().stream().map(request -> request.endpoint().wireName()).toList());
        }

        try (var quitting = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, request) -> switch (request.endpoint().wireName()) {
            case "status" -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
            case "quit" -> CompletableFuture.completedFuture(success(request, Map.of("status", "quitting")));
            default ->
                    CompletableFuture.failedFuture(new AssertionError("Unexpected endpoint: " + request.endpoint().wireName()));
        })) {
            ToolResult result = dispatch(ToolCatalog.load(DEFAULT_ENVIRONMENT, RuntimeToolModule.handlers(quitting.session(), MAPPER), MAPPER), "mc_quit_client", Map.of("waitForExit", false));
            assertEquals("Quit queued — the client is shutting down. Use mc_wait_for_bridge after relaunching to reconnect.", result.content().getFirst().text());
            assertFalse(result.isError());
            assertFalse(quitting.session().connectedPort().isPresent());
        }
    }

    @Test
    void appendsFiveSessionAndTwoAlwaysBoundDevHandlersWithEnvironmentGatedCatalogVisibility() throws Exception {
        try (var harness = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, request) -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id())))) {
            Map<String, ?> handlers = RuntimeToolModule.handlers(harness.session(), MAPPER);
            List<String> names = List.copyOf(handlers.keySet());
            List<String> taskNames = List.of("mc_join_server", "mc_leave_server", "mc_wait_until_in_world", "mc_quit_client", "mc_wait_for_bridge", "mc_script_logs", "mc_run_command");
            assertEquals(25, names.size());
            assertEquals(taskNames, names.subList(names.size() - taskNames.size(), names.size()));
            assertDoesNotThrow(handlers::clear);

            ToolCatalog defaultCatalog = ToolCatalog.load(DEFAULT_ENVIRONMENT, RuntimeToolModule.handlers(harness.session(), MAPPER), MAPPER);
            assertTrue(defaultCatalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_wait_for_bridge")));
            assertFalse(defaultCatalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_script_logs")));
            assertFalse(defaultCatalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_run_command")));
            assertEquals("Unknown tool: mc_run_command", dispatch(defaultCatalog, "mc_run_command", Map.of("command", "say hi")).content().getFirst().text());

            AppEnvironment devEnvironment = new AppEnvironment(Map.of("MCDEV_SESSION_LOG_DIR", Path.of(System.getProperty("java.io.tmpdir"), "mcdev-dev-session-logs").toString(), "MCDEV_RUN_COMMAND", "1"));
            ToolCatalog devCatalog = ToolCatalog.load(devEnvironment, RuntimeToolModule.handlers(harness.session(), MAPPER, devEnvironment), MAPPER);
            assertTrue(devCatalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_script_logs")));
            assertTrue(devCatalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_run_command")));
        }
    }

    @Test
    void executePerformsNoLogIoUntilTheScriptLogGateIsOptedIn(@TempDir Path temporary) throws Exception {
        try (var harness = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, request) -> switch (request.endpoint().wireName()) {
            case "status" -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
            case "execute" -> CompletableFuture.completedFuture(success(request, 2));
            default ->
                    CompletableFuture.failedFuture(new AssertionError("Unexpected endpoint: " + request.endpoint().wireName()));
        })) {
            // Session logging is opt-in: with no MCDEV_SESSION_LOG_DIR the logger is not
            // constructed and no script-log files are written anywhere.
            AppEnvironment disabled = new AppEnvironment(Map.of());
            ToolCatalog disabledCatalog = ToolCatalog.load(disabled, RuntimeToolModule.handlers(harness.session(), MAPPER, disabled), MAPPER);
            assertEquals("2", dispatch(disabledCatalog, "mc_execute", Map.of("code", "return 2")).content().getFirst().text());
            try (var files = java.nio.file.Files.walk(temporary)) {
                assertTrue(files.noneMatch(path -> path.getFileName().toString().equals("script-logs")));
            }

            AppEnvironment enabled = new AppEnvironment(Map.of("MCDEV_SESSION_LOG_DIR", temporary.resolve("enabled").toString()));
            ToolCatalog enabledCatalog = ToolCatalog.load(enabled, RuntimeToolModule.handlers(harness.session(), MAPPER, enabled), MAPPER);
            assertEquals("2", dispatch(enabledCatalog, "mc_execute", Map.of("code", "return 2")).content().getFirst().text());
            Path log = temporary.resolve("enabled").resolve("script-logs").resolve("all.jsonl");
            assertTrue(Files.exists(log));
            assertTrue(Files.readString(log).contains("\"code\":\"return 2\""));
            ToolResult paths = dispatch(enabledCatalog, "mc_script_logs", Map.of("mode", "paths"));
            assertTrue(paths.content().getFirst().text().contains("All executions: " + log));
        }
    }

    private static CompletableFuture<BridgeResponse> respond(RequestFixture request, BridgeFixture bridge, BridgeRequest wireRequest) {
        if (wireRequest.endpoint().wireName().equals("status")) {
            return CompletableFuture.completedFuture(RuntimeContractFixtures.status(wireRequest.id()));
        }
        if (!request.endpoint().equals(wireRequest.endpoint().wireName())) {
            return CompletableFuture.failedFuture(new AssertionError("Unexpected endpoint for " + request.label() + ": " + wireRequest.endpoint().wireName()));
        }
        return CompletableFuture.completedFuture(new BridgeResponse(wireRequest.id(), bridge.success(), bridge.resultPresent(), bridge.result(), bridge.output(), bridge.error()));
    }

    private static BridgeResponse success(BridgeRequest request, Object result) {
        return new BridgeResponse(request.id(), true, true, result, null, null);
    }

    private static BridgeResponse disabledStatus(BridgeRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", "1.21.11");
        result.put("mappingStatus", "mojang");
        result.put("obfuscated", true);
        result.put("refs", 0);
        result.put("gameDir", "C:\\Game");
        result.put("sessionControlEnabled", false);
        return new BridgeResponse(request.id(), true, true, result, null, null);
    }

    private static ToolResult dispatch(ToolCatalog catalog, String tool, Map<String, Object> arguments) throws Exception {
        return catalog.dispatch(tool, arguments, Cancellation.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static void assertWireRequest(RequestFixture fixture, List<BridgeRequest> actual) throws IOException {
        assertEquals(2, actual.size(), fixture.label());
        assertEquals("status", actual.getFirst().endpoint().wireName(), fixture.label());
        BridgeRequest target = actual.getLast();
        assertEquals(fixture.endpoint(), target.endpoint().wireName(), fixture.label());
        assertEquals(MAPPER.writeValueAsString(fixture.payload()), MAPPER.writeValueAsString(target.payload()), fixture.label());
    }

    private record RequestFixture(String label, String tool, Map<String, Object> arguments, String endpoint, Map<String, Object> payload) {
    }

    private record BridgeFixture(String label, boolean success, boolean resultPresent, Object result, String output, String error) {
    }

    private record ResultFixture(String label, String text, boolean isError) {
    }
}
