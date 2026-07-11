package dev.mcdevmcp.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class McpStdioIntegrationTest {
    private static final Path JAR = Path.of(System.getProperty("mcdevMcpJar"));
    private static final Path JAVA = Path.of(System.getProperty("mcdevMcpJava"));

    private static void assertProtocolMatches(String contractName, JsonObject actual, boolean normalizeVersion) throws IOException {
        var expected = readContract(contractName);
        expected.remove("id");
        actual = actual.deepCopy();
        actual.remove("id");
        if (normalizeVersion) {
            expected.getAsJsonObject("result").getAsJsonObject("serverInfo").addProperty("version", System.getProperty("mcdevMcpVersion"));
        }
        assertEquals(ToolCatalogContractTest.normalize(expected), ToolCatalogContractTest.normalize(actual));
    }

    private static JsonObject readJsonLine(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        assertNotNull(line, "server closed STDOUT before responding");
        JsonElement parsed = JsonParser.parseString(line);
        assertTrue(parsed.isJsonObject(), () -> "non-JSON STDOUT line: " + line);
        return parsed.getAsJsonObject();
    }

    private static JsonObject readContract(String name) throws IOException {
        try (var input = McpStdioIntegrationTest.class.getResourceAsStream("/contracts/mcp/" + name)) {
            if (input == null) {
                throw new IOException("Missing contract: " + name);
            }
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static void write(OutputStreamWriter writer, JsonObject message) throws IOException {
        writer.write(message + System.lineSeparator());
        writer.flush();
    }

    private static JsonObject request(int id, String method, JsonObject params) {
        var request = object("jsonrpc", "2.0");
        request.addProperty("id", id);
        request.addProperty("method", method);
        request.add("params", params);
        return request;
    }

    private static JsonObject initializedNotification() {
        var notification = object("jsonrpc", "2.0");
        notification.addProperty("method", "notifications/initialized");
        notification.add("params", new JsonObject());
        return notification;
    }

    private static JsonObject initializeParams() {
        var params = object("protocolVersion", "2024-11-05");
        params.add("capabilities", new JsonObject());
        var clientInfo = object("name", "contract-test");
        clientInfo.addProperty("version", "1");
        params.add("clientInfo", clientInfo);
        return params;
    }

    private static JsonObject object(String firstKey, String firstValue) {
        var object = new JsonObject();
        object.addProperty(firstKey, firstValue);
        return object;
    }

    @Test
    void shadedJarServesOnlyJsonRpcOverStdio() throws Exception {
        var process = new ProcessBuilder(JAVA.toString(), "-jar", JAR.toString(), "serve").start();
        try {
            var writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            try (var reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                try {
                    write(writer, request(1, "initialize", initializeParams()));
                    var initialize = readJsonLine(reader);
                    write(writer, initializedNotification());
                    write(writer, request(2, "tools/list", new JsonObject()));
                    var tools = readJsonLine(reader);
                    write(writer, request(3, "resources/list", new JsonObject()));
                    var resources = readJsonLine(reader);
                    write(writer, request(
                            4,
                            "resources/read",
                            object("uri", "mcdev://guides/python-scripting")));
                    var resource = readJsonLine(reader);
                    write(writer, request(5, "tools/call", object("name", "not_a_tool")));
                    var unknownTool = readJsonLine(reader);

                    assertProtocolMatches("initialize.json", initialize, true);
                    assertProtocolMatches("tools-list-default.json", tools, false);
                    assertProtocolMatches("resources-list.json", resources, false);
                    assertProtocolMatches("resource-python-scripting.json", resource, false);
                    assertTrue(unknownTool.getAsJsonObject("result").get("isError").getAsBoolean());
                    assertEquals(
                            "Unknown tool: not_a_tool",
                            unknownTool.getAsJsonObject("result")
                                    .getAsJsonArray("content")
                                    .get(0)
                                    .getAsJsonObject()
                                    .get("text")
                                    .getAsString());
                } finally {
                    writer.close();
                }

                assertTrue(
                        process.waitFor(Duration.ofSeconds(15)),
                        "shaded JAR did not stop after STDIN closed");
                assertEquals(
                        "",
                        reader.lines().collect(Collectors.joining("\n")),
                        "serve emitted trailing data on STDOUT");
            }

            assertEquals(0, process.exitValue());
            assertArrayEquals(
                    new byte[0],
                    process.getErrorStream().readAllBytes(),
                    "serve emitted diagnostics to STDERR");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(Duration.ofSeconds(5));
            }
        }
    }

    @Test
    void incompleteHandlerDoesNotBlockAnotherRequestAndCancellationCancelsItsFuture() throws Exception {
        var pending = new CompletableFuture<ToolResult>();
        var cancellation = new AtomicReference<Cancellation>();
        var adapter = new McpSdkAdapter();
        var slow = new ToolDefinition("slow", "slow", new JsonObject(), (_, signal) -> {
            cancellation.set(signal);
            return pending;
        }, ToolAvailability.ALWAYS);
        var fast = new ToolDefinition(
                "fast",
                "fast",
                new JsonObject(),
                (_, _) -> ToolHandlers.completed(ToolResult.text("fast")),
                ToolAvailability.ALWAYS);

        var slowRequest = McpSchema.CallToolRequest.builder("slow")
                .arguments(Map.of())
                .build();
        var fastRequest = McpSchema.CallToolRequest.builder("fast")
                .arguments(Map.of())
                .build();
        var slowSubscription = adapter.callHandler(slow).apply(null, slowRequest).subscribe();
        var fastResult = adapter.callHandler(fast)
                .apply(null, fastRequest)
                .toFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals("fast", ((McpSchema.TextContent) fastResult.content().getFirst()).text());
        slowSubscription.dispose();
        assertTrue(pending.isCancelled());
        assertTrue(cancellation.get().isCancelled());
    }

    @Test
    void synchronousAndAsynchronousHandlerFailuresBecomeToolErrors() throws Exception {
        ToolDefinition synchronous = new ToolDefinition(
                "sync",
                "sync",
                object("type", "object"),
                (_, _) -> {
                    throw new IllegalStateException("sync failure");
                },
                ToolAvailability.ALWAYS);
        ToolDefinition asynchronous = new ToolDefinition(
                "async",
                "async",
                object("type", "object"),
                (_, _) ->
                        CompletableFuture.failedFuture(new IllegalStateException("async failure")),
                ToolAvailability.ALWAYS);
        var adapter = new McpSdkAdapter();

        var syncResult = adapter.callHandler(synchronous)
                .apply(null, McpSchema.CallToolRequest.builder("sync").arguments(Map.of()).build())
                .toFuture()
                .get(5, TimeUnit.SECONDS);
        var asyncResult = adapter.callHandler(asynchronous)
                .apply(null, McpSchema.CallToolRequest.builder("async").arguments(Map.of()).build())
                .toFuture()
                .get(5, TimeUnit.SECONDS);

        assertTrue(syncResult.isError());
        assertEquals(
                "Error executing sync: sync failure",
                ((McpSchema.TextContent) syncResult.content().getFirst()).text());
        assertTrue(asyncResult.isError());
        assertEquals(
                "Error executing async: async failure",
                ((McpSchema.TextContent) asyncResult.content().getFirst()).text());
    }
}
