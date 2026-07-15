package dev.mcdevmcp.mcp;

import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class McpStdioIntegrationTest {
    private static final Path JAR = Path.of(System.getProperty("mcdevMcpJar"));
    private static final Path JAVA = Path.of(System.getProperty("mcdevMcpJava"));
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {
    };
    private static final TypeRef<List<Map<String, Object>>> LIST_OF_MAPS_TYPE = new TypeRef<>() {
    };
    
    private static void assertProtocolMatches(String contractName, Map<String, Object> actual, boolean normalizeVersion) throws IOException {
        var expected = new LinkedHashMap<>(ToolCatalogContractTest.readContract(contractName));
        expected.remove("id");
        actual = new LinkedHashMap<>(actual);
        actual.remove("id");
        if (normalizeVersion) {
            var result = new LinkedHashMap<>(MAPPER.convertValue(expected.get("result"), MAP_TYPE));
            var serverInfo = new LinkedHashMap<>(MAPPER.convertValue(result.get("serverInfo"), MAP_TYPE));
            serverInfo.put("version", System.getProperty("mcdevMcpVersion"));
            result.put("serverInfo", serverInfo);
            expected.put("result", result);
        }
        assertEquals(ToolCatalogContractTest.normalize(expected), ToolCatalogContractTest.normalize(actual));
    }
    
    private static Map<String, Object> readJsonLine(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        assertNotNull(line, "server closed STDOUT before responding");
        return MAPPER.readValue(line, MAP_TYPE);
    }
    
    private static void write(OutputStreamWriter writer, Map<String, Object> message) throws IOException {
        writer.write(MAPPER.writeValueAsString(message) + System.lineSeparator());
        writer.flush();
    }
    
    private static Map<String, Object> request(int id, String method, Map<String, Object> params) {
        var request = new LinkedHashMap<String, Object>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);
        return request;
    }
    
    private static Map<String, Object> initializedNotification() {
        return Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of());
    }
    
    private static Map<String, Object> initializeParams() {
        return Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(), "clientInfo", Map.of("name", "contract-test", "version", "1"));
    }
    
    private static ToolBinding<TestEmptyArguments> binding(ToolHandler<TestEmptyArguments> handler) {
        return new ToolBinding<>(ArgumentDecoder.sdk(TestEmptyArguments.class), handler);
    }
    
    @Test
    void shadedJarServesOnlyJsonRpcOverStdio() throws Exception {
        var process = new ProcessBuilder(JAVA.toString(), "-jar", JAR.toString(), "serve").start();
        try {
            var writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            try (var reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                try {
                    write(writer, request(1, "initialize", initializeParams()));
                    var initialize = readJsonLine(reader);
                    write(writer, initializedNotification());
                    write(writer, request(2, "tools/list", Map.of()));
                    var tools = readJsonLine(reader);
                    write(writer, request(3, "resources/list", Map.of()));
                    var resources = readJsonLine(reader);
                    write(writer, request(4, "resources/read", Map.of("uri", "mcdev://guides/python-scripting")));
                    var resource = readJsonLine(reader);
                    write(writer, request(5, "tools/call", Map.of("name", "not_a_tool")));
                    var unknownTool = readJsonLine(reader);
                    
                    assertProtocolMatches("initialize.json", initialize, true);
                    assertProtocolMatches("tools-list-default.json", tools, false);
                    assertProtocolMatches("resources-list.json", resources, false);
                    assertProtocolMatches("resource-python-scripting.json", resource, false);
                    var unknownResult = MAPPER.convertValue(unknownTool.get("result"), MAP_TYPE);
                    assertEquals(true, unknownResult.get("isError"));
                    assertEquals("Unknown tool: not_a_tool", MAPPER.convertValue(unknownResult.get("content"), LIST_OF_MAPS_TYPE).getFirst().get("text"));
                } finally {
                    writer.close();
                }
                
                assertTrue(process.waitFor(Duration.ofSeconds(15)), "shaded JAR did not stop after STDIN closed");
                assertEquals("", reader.lines().collect(Collectors.joining("\n")), "serve emitted trailing data on STDOUT");
            }
            
            assertEquals(0, process.exitValue());
            assertArrayEquals(new byte[0], process.getErrorStream().readAllBytes(), "serve emitted diagnostics to STDERR");
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
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var adapter = new McpSdkAdapter(MAPPER, executor);
            var slow = new ToolDefinition("slow", "slow", Map.of("type", "object"), binding((_, signal) -> {
                cancellation.set(signal);
                return pending;
            }), ToolAvailability.ALWAYS);
            var fast = new ToolDefinition("fast", "fast", Map.of("type", "object"), binding((_, _) -> ToolHandlers.completed(ToolResult.text("fast"))), ToolAvailability.ALWAYS);
            
            var slowRequest = McpSchema.CallToolRequest.builder("slow").arguments(Map.of()).build();
            var fastRequest = McpSchema.CallToolRequest.builder("fast").arguments(Map.of()).build();
            var slowSubscription = adapter.callHandler(slow).apply(null, slowRequest).subscribe();
            var fastResult = adapter.callHandler(fast).apply(null, fastRequest).toFuture().get(5, TimeUnit.SECONDS);
            
            assertEquals("fast", ((McpSchema.TextContent) fastResult.content().getFirst()).text());
            slowSubscription.dispose();
            assertTrue(pending.isCancelled());
            assertTrue(cancellation.get().isCancelled());
        }
    }
    
    @Test
    void synchronousAndAsynchronousHandlerFailuresBecomeToolErrors() throws Exception {
        var synchronous = new ToolDefinition("sync", "sync", Map.of("type", "object"), binding((_, _) -> {
            throw new IllegalStateException("sync failure");
        }), ToolAvailability.ALWAYS);
        var asynchronous = new ToolDefinition("async", "async", Map.of("type", "object"), binding((_, _) -> CompletableFuture.failedFuture(new IllegalStateException("async failure"))), ToolAvailability.ALWAYS);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var adapter = new McpSdkAdapter(MAPPER, executor);
            
            var syncResult = adapter.callHandler(synchronous).apply(null, McpSchema.CallToolRequest.builder("sync").arguments(Map.of()).build()).toFuture().get(5, TimeUnit.SECONDS);
            var asyncResult = adapter.callHandler(asynchronous).apply(null, McpSchema.CallToolRequest.builder("async").arguments(Map.of()).build()).toFuture().get(5, TimeUnit.SECONDS);
            
            assertTrue(syncResult.isError());
            assertEquals("Error executing sync: sync failure", ((McpSchema.TextContent) syncResult.content().getFirst()).text());
            assertTrue(asyncResult.isError());
            assertEquals("Error executing async: async failure", ((McpSchema.TextContent) asyncResult.content().getFirst()).text());
        }
    }
}
