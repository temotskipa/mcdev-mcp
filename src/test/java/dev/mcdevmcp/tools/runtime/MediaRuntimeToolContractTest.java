package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeRequest;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.bridge.BridgeTestHarness;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.CompleteToolBindings;
import dev.mcdevmcp.mcp.tool.ToolContent;
import dev.mcdevmcp.mcp.tool.ToolContentType;
import dev.mcdevmcp.mcp.tool.ToolResult;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaRuntimeToolContractTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final AppEnvironment ENVIRONMENT = new AppEnvironment(Map.of());

    @Test
    void replaysTheFrozenMediaCorpusWithExactPayloadsTimeoutsAndContent() throws Exception {
        List<RequestFixture> requests = RuntimeContractFixtures.load(MAPPER, "contracts/runtime-tools/media-requests.jsonl", RequestFixture.class);
        List<BridgeFixture> bridgeResponses = RuntimeContractFixtures.load(MAPPER, "contracts/runtime-tools/media-bridge-responses.jsonl", BridgeFixture.class);
        List<ResultFixture> results = RuntimeContractFixtures.load(MAPPER, "contracts/runtime-tools/media-tool-results.jsonl", ResultFixture.class);
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
                ToolResult actual = dispatch(catalog, request.tool(), request.arguments());

                assertContent(expected, actual);
                assertWireRequest(request, harness.requests());
                assertEquals(expectedEffectiveTimeouts(request), harness.effectiveTimeouts(), request.label());
                assertEquals(List.of(9876), harness.openedPorts(), request.label());
            }
        }
    }

    @Test
    void rejectsOversizedTextureBeforeCreatingMcpImageContent() throws Exception {
        String oversized = "a".repeat(MediaToolSupport.MAX_BASE64_PNG_BYTES + 1);
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, request) -> {
            if (request.endpoint().wireName().equals("status")) {
                return CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
            }
            return CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, Map.of("base64Png", oversized, "width", 16, "height", 16, "spriteName", "minecraft:item/diamond"), null, null));
        })) {
            ToolCatalog catalog = ToolCatalog.load(ENVIRONMENT, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER)), MAPPER);

            ToolResult result = dispatch(catalog, "mc_get_item_texture", Map.of("slot", 0));

            assertTrue(result.isError());
            assertEquals("Bridge 'getItemTexture' returned a 7.0 MB base64 PNG, exceeding the 7.0 MB cap. This usually means a malformed bridge response — please report it.", result.content().getFirst().text());
            assertEquals(1, result.content().size());
            assertEquals(ToolContentType.TEXT, result.content().getFirst().type());
        }
    }

    @Test
    void normalizesFlattenedIntervalsAndScalesRecordingDeadlinesLikeTheNodeOracle() {
        assertEquals("frame", intervalValue(MediaToolSupport.normalizeRecordInterval("frame")));
        assertEquals(BigDecimal.valueOf(80), intervalValue(MediaToolSupport.normalizeRecordInterval("80")));
        assertEquals(33.4, ((BigDecimal) intervalValue(MediaToolSupport.normalizeRecordInterval("33.4"))).doubleValue());
        assertEquals(BigDecimal.valueOf(16), intervalValue(MediaToolSupport.normalizeRecordInterval("0x10")));
        assertEquals(BigDecimal.valueOf(2), intervalValue(MediaToolSupport.normalizeRecordInterval("0b10")));
        assertEquals(BigDecimal.valueOf(8), intervalValue(MediaToolSupport.normalizeRecordInterval("0o10")));
        assertEquals("1d", intervalValue(MediaToolSupport.normalizeRecordInterval("1d")));
        assertEquals("fast", intervalValue(MediaToolSupport.normalizeRecordInterval("fast")));
        assertEquals("", intervalValue(MediaToolSupport.normalizeRecordInterval("")));
        assertEquals("  ", intervalValue(MediaToolSupport.normalizeRecordInterval("  ")));
        assertEquals(Duration.ofMillis(25_000), MediaToolSupport.recordingDeadline(BigDecimal.valueOf(100), MediaToolSupport.normalizeRecordInterval(100)));
        assertEquals(Duration.ofMillis(20_100), MediaToolSupport.recordingDeadline(BigDecimal.valueOf(300), MediaToolSupport.normalizeRecordInterval("frame")));
        assertEquals(Duration.ofMillis(15_153), MediaToolSupport.recordingDeadline(BigDecimal.valueOf(9), null));
        assertEquals(Duration.ofMillis(15_170), MediaToolSupport.recordingDeadline(BigDecimal.valueOf(10), MediaToolSupport.normalizeRecordInterval("fast")));
    }

    @Test
    void appendsExactlyEightMutableMediaBindingsAfterTheCoreGroup() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, request) -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id())))) {
            Map<String, ?> handlers = RuntimeToolModule.handlers(harness.session(), MAPPER);
            List<String> names = List.copyOf(handlers.keySet());
            List<String> mediaNames = List.of("mc_screenshot", "mc_record_video", "mc_get_item_texture", "mc_get_entity_item_texture", "mc_get_item_texture_by_id", "mc_set_entity_glow", "mc_set_block_glow", "mc_clear_block_glow");

            assertEquals(25, names.size());
            assertEquals(mediaNames, names.subList(10, 10 + mediaNames.size()));
            assertDoesNotThrow(handlers::clear);
        }
    }

    private static ToolResult dispatch(ToolCatalog catalog, String tool, Map<String, Object> arguments) throws Exception {
        return catalog.dispatch(tool, arguments, Cancellation.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static CompletableFuture<BridgeResponse> respond(RequestFixture request, BridgeFixture bridge, BridgeRequest wireRequest) {
        String endpoint = wireRequest.endpoint().wireName();
        if (endpoint.equals("status")) {
            return CompletableFuture.completedFuture(RuntimeContractFixtures.status(wireRequest.id()));
        }
        if (!request.endpoint().equals(endpoint)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Unexpected endpoint " + endpoint + " for " + request.label()));
        }
        return CompletableFuture.completedFuture(new BridgeResponse(wireRequest.id(), Boolean.TRUE.equals(bridge.success()), Boolean.TRUE.equals(bridge.resultPresent()), bridge.result(), bridge.output(), bridge.error()));
    }

    private static void assertWireRequest(RequestFixture fixture, List<BridgeRequest> actual) {
        assertEquals(2, actual.size(), fixture.label());
        assertEquals("status", actual.getFirst().endpoint().wireName(), fixture.label());
        assertEquals(Map.of(), actual.getFirst().payload(), fixture.label());
        assertEquals(fixture.endpoint(), actual.getLast().endpoint().wireName(), fixture.label());
        assertEquals(writeJson(fixture.payload()), writeJson(actual.getLast().payload()), fixture.label());
    }

    private static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException exception) {
            throw new AssertionError("Unable to serialize contract payload", exception);
        }
    }

    private static List<Duration> expectedEffectiveTimeouts(RequestFixture fixture) {
        Duration target = Duration.ofSeconds(10);
        if (fixture.endpoint().equals("record_video")) {
            Duration requested = MediaToolSupport.recordingDeadline(RuntimeToolSupport.requiredDecimal(fixture.payload().get("frames"), "frames"), MediaToolSupport.normalizeRecordInterval(fixture.payload().get("interval")));
            Duration extended = requested.plusSeconds(5);
            target = extended.compareTo(Duration.ofMinutes(5)) > 0 ? Duration.ofMinutes(5) : extended;
        }
        return List.of(Duration.ofSeconds(10), target);
    }

    private static void assertContent(ResultFixture expected, ToolResult actual) {
        assertEquals(expected.isError(), actual.isError(), expected.label());
        assertEquals(expected.content().size(), actual.content().size(), expected.label());
        for (int index = 0; index < expected.content().size(); index++) {
            ContentFixture wanted = expected.content().get(index);
            ToolContent observed = actual.content().get(index);
            assertEquals(ToolContentType.valueOf(wanted.type()), observed.type(), expected.label() + " content " + index);
            assertEquals(wanted.text(), observed.text(), expected.label() + " content " + index);
            assertEquals(wanted.mimeType(), observed.mimeType(), expected.label() + " content " + index);
            assertEquals(wanted.data(), observed.data(), expected.label() + " content " + index);
        }
    }

    private static Object intervalValue(RecordInterval interval) {
        return interval == null ? null : interval.bridgeValue();
    }

    private record RequestFixture(String label, String tool, Map<String, Object> arguments, String endpoint, Map<String, Object> payload) {
    }

    private record BridgeFixture(String label, Boolean success, Boolean resultPresent, Object result, String output, String error) {
    }

    private record ResultFixture(String label, List<ContentFixture> content, boolean isError) {
    }

    private record ContentFixture(String type, String text, String mimeType, String data) {
    }
}
