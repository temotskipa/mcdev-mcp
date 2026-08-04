package dev.mcdevmcp.parity;

import dev.mcdevmcp.mcp.McpContractTestSupport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("parity")
@ResourceLock("node-oracle-materializer")
class DifferentialMcpTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {
    };
    private static final Set<String> STATIC_TOOLS = Set.of("mc_version", "mc_search", "mc_get_class", "mc_get_method", "mc_find_refs", "mc_list_classes", "mc_list_packages", "mc_find_hierarchy");
    private static final Set<String> RUNTIME_TOOLS = Set.of("mc_connect", "mc_execute", "mc_snapshot", "mc_screenshot", "mc_record_video", "mc_nearby_entities", "mc_entity_details", "mc_nearby_blocks", "mc_block_details", "mc_looked_at_entity", "mc_set_entity_glow", "mc_set_block_glow", "mc_clear_block_glow", "mc_get_item_texture", "mc_get_entity_item_texture", "mc_get_item_texture_by_id", "mc_chat_history", "mc_screen_inspect", "mc_join_server", "mc_leave_server", "mc_wait_until_in_world", "mc_quit_client", "mc_wait_for_bridge", "mc_script_logs", "mc_run_command");
    private static final Map<String, Set<StaticOutcome>> REQUIRED_PROCESS_STATIC_OUTCOMES = Map.of("mc_version", Set.of(StaticOutcome.SUCCESS, StaticOutcome.ERROR), "mc_search", Set.of(StaticOutcome.SUCCESS, StaticOutcome.ERROR, StaticOutcome.EMPTY, StaticOutcome.TRUNCATED), "mc_get_class", Set.of(StaticOutcome.SUCCESS, StaticOutcome.ERROR, StaticOutcome.EMPTY), "mc_get_method", Set.of(StaticOutcome.SUCCESS, StaticOutcome.ERROR, StaticOutcome.EMPTY), "mc_find_refs", Set.of(StaticOutcome.SUCCESS, StaticOutcome.ERROR, StaticOutcome.EMPTY, StaticOutcome.TRUNCATED), "mc_list_classes", Set.of(StaticOutcome.SUCCESS, StaticOutcome.ERROR, StaticOutcome.EMPTY, StaticOutcome.TRUNCATED), "mc_list_packages", Set.of(StaticOutcome.SUCCESS, StaticOutcome.ERROR, StaticOutcome.EMPTY, StaticOutcome.TRUNCATED), "mc_find_hierarchy", Set.of(StaticOutcome.SUCCESS, StaticOutcome.ERROR, StaticOutcome.EMPTY, StaticOutcome.TRUNCATED));

    @TempDir
    Path temporaryDirectory;

    @Test
    void matchesThePinnedNodeServerAcrossTheCompleteMcpSurface() throws Exception {
        List<Scenario> scenarios = scenarios();
        Path nodeRoot = prepareProcessRoot("node");
        Path javaRoot = prepareProcessRoot("java");

        try (NodeOracleMaterializer oracle = NodeOracleMaterializer.materialize()) {
            StaticParityFixture.prepareNode(nodeRoot, oracle);
            StaticParityFixture.prepareJava(javaRoot);
            try (ScriptedDebugBridge nodeBridge = ScriptedDebugBridge.start(nodeRoot.resolve("bridge"));
                 ScriptedDebugBridge javaBridge = ScriptedDebugBridge.start(javaRoot.resolve("bridge"));
                 McpProcessClient node = McpProcessClient.startAllowingForcedShutdown(configure(oracle.nodeProcess("dist/cli.js", "serve"), nodeRoot, nodeBridge.port()));
                 McpProcessClient java = McpProcessClient.start(configure(javaProcess(javaRoot), javaRoot, javaBridge.port()))) {
                Map<String, Map<String, Object>> nodeResponses = new LinkedHashMap<>();

                for (Scenario scenario : scenarios) {
                    Map<String, Object> nodeResponse = execute(node, scenario, nodeBridge.port());
                    Map<String, Object> javaResponse = execute(java, scenario, javaBridge.port());
                    nodeResponses.put(scenario.label(), nodeResponse);
                    if (scenario.kind().equals("static")) {
                        assertStaticOutcome(scenario, nodeResponse, "Node");
                        assertStaticOutcome(scenario, javaResponse, "Java");
                    }
                    if (scenario.kind().equals("initialize")) {
                        assertInitializeVersions(nodeResponse, javaResponse);
                    }
                    switch (scenario.comparison()) {
                        case "exact" ->
                                assertEquivalent(scenario, nodeResponse, javaResponse, nodeRoot, javaRoot, nodeBridge.port(), javaBridge.port());
                        case "find_refs_descriptors" ->
                                assertDescriptorUpgrade(scenario, nodeResponse, javaResponse, nodeRoot, javaRoot, nodeBridge.port(), javaBridge.port());
                        case "java_launcher" ->
                                assertJavaLauncherUpgrade(scenario, nodeResponse, javaResponse, nodeRoot, javaRoot, nodeBridge.port(), javaBridge.port());
                        case "sdk_input_validation" ->
                                assertSdkInputValidationUpgrade(scenario, nodeResponse, javaResponse, nodeRoot, javaRoot, nodeBridge.port(), javaBridge.port());
                        case "pinned_guide_links" ->
                                assertPinnedGuideLinks(scenario, nodeResponse, javaResponse, nodeRoot, javaRoot, nodeBridge.port(), javaBridge.port());
                        case "reviewed_guide" ->
                                assertReviewedGuide(scenario, nodeResponse, javaResponse, nodeRoot, javaRoot, nodeBridge.port(), javaBridge.port());
                        default ->
                                throw new IllegalStateException("Unsupported parity comparison: " + scenario.comparison());
                    }
                }

                assertCatalogCoverage(nodeResponses.get("tools-list"), scenarios);
                assertStaticProcessCoverage(scenarios);
                assertEquals(normalizeValue(node.awaitQuiescence(), nodeRoot, nodeBridge.port()), normalizeValue(java.awaitQuiescence(), javaRoot, javaBridge.port()), "Node and Java must emit the same MCP notifications and no unmatched responses");
                assertEquals(nodeBridge.invocations(), javaBridge.invocations(), "Node and Java must make the same DebugBridge calls in the same order");
                nodeBridge.assertHealthy();
                javaBridge.assertHealthy();
                assertTrue(node.stderr().isBlank(), () -> "Node server STDERR was not clean:\n" + node.stderr());
                assertTrue(java.stderr().isBlank(), () -> "Java server STDERR was not clean:\n" + java.stderr());
                nodeBridge.shutdown();
                javaBridge.shutdown();
            }
        }
    }

    private Path prepareProcessRoot(String name) throws IOException {
        Path root = temporaryDirectory.resolve(name).toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("home"));
        Files.createDirectories(root.resolve("local-app-data"));
        Files.createDirectories(root.resolve("roaming-app-data"));
        Files.createDirectories(root.resolve("xdg-cache"));
        Files.createDirectories(root.resolve("tmp"));
        Files.createDirectories(root.resolve("bridge"));
        return root;
    }

    private static ProcessBuilder configure(ProcessBuilder builder, Path root, int bridgePort) {
        Map<String, String> environment = builder.environment();
        environment.keySet().removeIf(name -> {
            String normalized = name.toUpperCase(Locale.ROOT);
            return normalized.startsWith("MCDEV_") || normalized.equals("DEBUGBRIDGE_PORT") || normalized.equals("NODE_OPTIONS") || normalized.equals("JAVA_TOOL_OPTIONS") || normalized.equals("_JAVA_OPTIONS") || normalized.equals("JDK_JAVA_OPTIONS");
        });
        environment.put("HOME", root.resolve("home").toString());
        environment.put("USERPROFILE", root.resolve("home").toString());
        environment.put("LOCALAPPDATA", root.resolve("local-app-data").toString());
        environment.put("APPDATA", root.resolve("roaming-app-data").toString());
        environment.put("XDG_CACHE_HOME", root.resolve("xdg-cache").toString());
        environment.put("TEMP", root.resolve("tmp").toString());
        environment.put("TMP", root.resolve("tmp").toString());
        environment.put("DEBUGBRIDGE_PORT", Integer.toString(bridgePort));
        environment.put("MCDEV_SCRIPT_LOGS", "1");
        environment.put("MCDEV_RUN_COMMAND", "1");
        return builder;
    }

    private static ProcessBuilder javaProcess(Path root) {
        String executable = requiredProperty("mcdevMcpJava");
        Path jar = Path.of(requiredProperty("mcdevMcpJar")).toAbsolutePath().normalize();
        return new ProcessBuilder(executable, "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US", "-Duser.home=" + root.resolve("home"), "-Djava.io.tmpdir=" + root.resolve("tmp"), "-jar", jar.toString(), "serve");
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing Gradle test property '" + name + "'");
        }
        return value;
    }

    private static Map<String, Object> execute(McpProcessClient client, Scenario scenario, int bridgePort) throws IOException {
        Map<String, Object> request = replaceBridgePort(scenario.request(), bridgePort);
        if (scenario.kind().equals("initialize")) {
            return client.initialize(map(request.get("params")));
        }
        return client.request(request);
    }

    private static void assertInitializeVersions(Map<String, Object> nodeResponse, Map<String, Object> javaResponse) {
        assertEquals("2.2.1", serverVersion(nodeResponse), "Pinned Node oracle version changed");
        assertEquals(requiredProperty("mcdevMcpVersion"), serverVersion(javaResponse), "Java initialize version must come from the build");
    }

    private static String serverVersion(Map<String, Object> response) {
        return Objects.toString(map(map(response.get("result")).get("serverInfo")).get("version"));
    }

    private static void assertEquivalent(Scenario scenario, Map<String, Object> nodeResponse, Map<String, Object> javaResponse, Path nodeRoot, Path javaRoot, int nodePort, int javaPort) throws IOException {
        Map<String, Object> normalizedNode = normalize(nodeResponse, scenario, nodeRoot, nodePort);
        Map<String, Object> normalizedJava = normalize(javaResponse, scenario, javaRoot, javaPort);
        String difference = firstDifference(normalizedNode, normalizedJava, "");
        if (difference == null) {
            return;
        }
        Path report = writeReport(scenario, nodeResponse, javaResponse, normalizedNode, normalizedJava, difference);
        fail("MCP parity mismatch for '" + scenario.label() + "' at " + difference + ". Report: " + report);
    }

    private static void assertDescriptorUpgrade(Scenario scenario, Map<String, Object> nodeResponse, Map<String, Object> javaResponse, Path nodeRoot, Path javaRoot, int nodePort, int javaPort) throws IOException {
        String expectedNode;
        String expectedJava;
        if (scenario.label().equals("static-refs-callers-descriptors")) {
            expectedNode = "Found 2 callers:\ncaller.Described.entry (line 11)\ncaller.Legacy.entry (line 12)\nTotal: 2 callers";
            expectedJava = "Found 2 callers:\ncaller.Described.entry()V (line 11)\ncaller.Legacy.entry (line 12)\nTotal: 2 callers";
        }
        else if (scenario.label().equals("static-refs-callees-descriptors")) {
            expectedNode = "Found 2 callees:\ncallee.First.work (line 21)\ncallee.Second.stop\nTotal: 2 callees";
            expectedJava = "Found 2 callees:\ncallee.First.work(Ljava/lang/String;)V (line 21)\ncallee.Second.stop\nTotal: 2 callees";
        }
        else {
            throw new IllegalArgumentException("Unknown descriptor-upgrade scenario: " + scenario.label());
        }
        assertEquals(expectedNode, toolText(nodeResponse), "Pinned Node descriptor rendering changed for " + scenario.label());
        assertEquals(expectedJava, toolText(javaResponse), "Java descriptor rendering must preserve the approved Task 7 improvement for " + scenario.label());

        Map<String, Object> normalizedNode = withApprovedToolText(normalize(nodeResponse, scenario, nodeRoot, nodePort), "$APPROVED_DESCRIPTOR_RENDERING");
        Map<String, Object> normalizedJava = withApprovedToolText(normalize(javaResponse, scenario, javaRoot, javaPort), "$APPROVED_DESCRIPTOR_RENDERING");
        String difference = firstDifference(normalizedNode, normalizedJava, "");
        if (difference != null) {
            Path report = writeReport(scenario, nodeResponse, javaResponse, normalizedNode, normalizedJava, difference);
            fail("MCP parity mismatch outside the approved descriptor text for '" + scenario.label() + "' at " + difference + ". Report: " + report);
        }
    }

    private static void assertJavaLauncherUpgrade(Scenario scenario, Map<String, Object> nodeResponse, Map<String, Object> javaResponse, Path nodeRoot, Path javaRoot, int nodePort, int javaPort) throws IOException {
        Map<String, Object> arguments = map(map(scenario.request().get("params")).get("arguments"));
        String version = Objects.toString(arguments.get("version"));
        String prefix = "Version " + version + " not initialized. STOP and ask the USER to run this command in their terminal:\n  ";
        String suffix = " init -v " + version + "\n\nThis will download, decompile, and index Minecraft " + version + " sources (including callgraph).";
        assertEquals(prefix + "node dist/cli.js" + suffix, toolText(nodeResponse), "Pinned Node launcher guidance changed for " + scenario.label());
        assertEquals(prefix + "java -jar mcdev-mcp-" + requiredProperty("mcdevMcpVersion") + ".jar" + suffix, toolText(javaResponse), "Java missing-cache guidance must use the distributable JAR launcher for " + scenario.label());

        Map<String, Object> normalizedNode = withApprovedToolText(normalize(nodeResponse, scenario, nodeRoot, nodePort), "$APPROVED_JAVA_LAUNCHER");
        Map<String, Object> normalizedJava = withApprovedToolText(normalize(javaResponse, scenario, javaRoot, javaPort), "$APPROVED_JAVA_LAUNCHER");
        String difference = firstDifference(normalizedNode, normalizedJava, "");
        if (difference != null) {
            Path report = writeReport(scenario, nodeResponse, javaResponse, normalizedNode, normalizedJava, difference);
            fail("MCP parity mismatch outside the approved Java launcher guidance for '" + scenario.label() + "' at " + difference + ". Report: " + report);
        }
    }

    private static void assertSdkInputValidationUpgrade(Scenario scenario, Map<String, Object> nodeResponse, Map<String, Object> javaResponse, Path nodeRoot, Path javaRoot, int nodePort, int javaPort) throws IOException {
        assertEquals("Error executing mc_search: query.toLowerCase is not a function", toolText(nodeResponse), "Pinned Node malformed-input behavior changed");
        assertEquals("Tool (mc_search) input validation failed: Validation failed: JSON schema validation errors: [/query: integer found, string expected]", toolText(javaResponse), "Java must reject malformed tool arguments at the SDK schema boundary");

        Map<String, Object> normalizedNode = withApprovedToolText(normalize(nodeResponse, scenario, nodeRoot, nodePort), "$APPROVED_SDK_INPUT_VALIDATION");
        Map<String, Object> normalizedJava = withApprovedToolText(normalize(javaResponse, scenario, javaRoot, javaPort), "$APPROVED_SDK_INPUT_VALIDATION");
        String difference = firstDifference(normalizedNode, normalizedJava, "");
        if (difference != null) {
            Path report = writeReport(scenario, nodeResponse, javaResponse, normalizedNode, normalizedJava, difference);
            fail("MCP parity mismatch outside the approved SDK input validation for '" + scenario.label() + "' at " + difference + ". Report: " + report);
        }
    }

    private static void assertPinnedGuideLinks(Scenario scenario, Map<String, Object> nodeResponse, Map<String, Object> javaResponse, Path nodeRoot, Path javaRoot, int nodePort, int javaPort) throws IOException {
        String nodeText = resourceText(nodeResponse);
        String javaText = resourceText(javaResponse);
        String pinnedRoot = "https://github.com/use-ai-for-mc/mcdev-mcp/blob/7b98bdb4a1d885d588cd141d8eb21e3c5c18b2b6/src/tools/runtime/";
        assertTrue(javaText.contains(pinnedRoot), "Java guide must pin retired source links to the frozen Node revision");
        assertFalse(javaText.contains("](../src/tools/runtime/"), "Java guide must not expose links that are invalid outside the retired Node source tree");
        assertEquals(resourceText(McpContractTestSupport.readContract("resource-python-scripting.json")), javaText, "Java Python guide must match its reviewed distribution contract");
        assertNotEquals(nodeText, javaText, "Pinned Node and Java guide fixtures must continue exercising the approved distribution-guide difference");

        Map<String, Object> normalizedNode = withApprovedResourceText(normalize(nodeResponse, scenario, nodeRoot, nodePort), "$APPROVED_PINNED_GUIDE_LINKS");
        Map<String, Object> normalizedJava = withApprovedResourceText(normalize(javaResponse, scenario, javaRoot, javaPort), "$APPROVED_PINNED_GUIDE_LINKS");
        String difference = firstDifference(normalizedNode, normalizedJava, "");
        if (difference != null) {
            Path report = writeReport(scenario, nodeResponse, javaResponse, normalizedNode, normalizedJava, difference);
            fail("MCP parity mismatch outside the approved pinned guide links for '" + scenario.label() + "' at " + difference + ". Report: " + report);
        }
    }

    private static void assertReviewedGuide(Scenario scenario, Map<String, Object> nodeResponse, Map<String, Object> javaResponse, Path nodeRoot, Path javaRoot, int nodePort, int javaPort) throws IOException {
        String nodeText = resourceText(nodeResponse);
        String javaText = resourceText(javaResponse);
        assertEquals(resourceText(McpContractTestSupport.readContract("resource-dev-loop.json")), javaText, "Java dev-loop guide must match its reviewed distribution contract");
        assertNotEquals(nodeText, javaText, "Pinned Node and Java guide fixtures must continue exercising the approved reviewed-guide difference");

        Map<String, Object> normalizedNode = withApprovedResourceText(normalize(nodeResponse, scenario, nodeRoot, nodePort), "$APPROVED_REVIEWED_GUIDE");
        Map<String, Object> normalizedJava = withApprovedResourceText(normalize(javaResponse, scenario, javaRoot, javaPort), "$APPROVED_REVIEWED_GUIDE");
        String difference = firstDifference(normalizedNode, normalizedJava, "");
        if (difference != null) {
            Path report = writeReport(scenario, nodeResponse, javaResponse, normalizedNode, normalizedJava, difference);
            fail("MCP parity mismatch outside the approved reviewed guide for '" + scenario.label() + "' at " + difference + ". Report: " + report);
        }
    }

    private static String toolText(Map<String, Object> response) {
        Object content = map(response.get("result")).get("content");
        List<?> items = assertInstanceOf(List.class, content, "tools/call result must contain content");
        assertEquals(1, items.size(), "Parity tool response must contain exactly one content item");
        return Objects.toString(map(items.getFirst()).get("text"));
    }

    private static String resourceText(Map<String, Object> response) {
        Object contents = map(response.get("result")).get("contents");
        List<?> items = assertInstanceOf(List.class, contents, "resources/read result must contain contents");
        assertEquals(1, items.size(), "Parity resource response must contain exactly one content item");
        return Objects.toString(map(items.getFirst()).get("text"));
    }

    private static Map<String, Object> withApprovedResourceText(Map<String, Object> response, String placeholder) {
        Map<String, Object> copy = map(response);
        Map<String, Object> result = map(copy.get("result"));
        List<?> contents = assertInstanceOf(List.class, result.get("contents"));
        List<Object> replacedContents = new ArrayList<>(contents);
        Map<String, Object> item = map(replacedContents.getFirst());
        item.put("text", placeholder);
        replacedContents.set(0, item);
        result.put("contents", replacedContents);
        copy.put("result", result);
        return copy;
    }

    private static Map<String, Object> withApprovedToolText(Map<String, Object> response, String placeholder) {
        Map<String, Object> copy = map(response);
        Map<String, Object> result = map(copy.get("result"));
        List<?> content = assertInstanceOf(List.class, result.get("content"));
        List<Object> replacedContent = new ArrayList<>(content);
        Map<String, Object> item = map(replacedContent.getFirst());
        item.put("text", placeholder);
        replacedContent.set(0, item);
        result.put("content", replacedContent);
        copy.put("result", result);
        return copy;
    }

    private static Map<String, Object> normalize(Map<String, Object> response, Scenario scenario, Path root, int port) {
        Map<String, Object> normalized = map(normalizeValue(response, root, port));
        if (normalized.containsKey("id")) {
            normalized.put("id", "$JSON_RPC_ID");
        }
        if (scenario.kind().equals("initialize")) {
            Map<String, Object> result = map(normalized.get("result"));
            Map<String, Object> serverInfo = map(result.get("serverInfo"));
            serverInfo.put("version", "$SERVER_VERSION");
            result.put("serverInfo", serverInfo);
            normalized.put("result", result);
        }
        return normalized;
    }

    private static Object normalizeValue(Object value, Path root, int port) {
        if (value instanceof Map<?, ?> object) {
            var normalized = new LinkedHashMap<String, Object>();
            object.forEach((key, child) -> normalized.put((String) key, normalizeValue(child, root, port)));
            return normalized;
        }
        if (value instanceof List<?> array) {
            return array.stream().map(child -> normalizeValue(child, root, port)).toList();
        }
        if (value instanceof String text) {
            return normalizeText(text, root, port);
        }
        return value;
    }

    private static String normalizeText(String text, Path root, int port) {
        String nativeRoot = root.toString();
        String slashRoot = nativeRoot.replace('\\', '/');
        String normalized = text.replace(nativeRoot, "$PROCESS_ROOT").replace(slashRoot, "$PROCESS_ROOT");
        normalized = normalized.replace("127.0.0.1:" + port, "127.0.0.1:$DEBUGBRIDGE_PORT");
        normalized = normalized.replace("localhost:" + port, "localhost:$DEBUGBRIDGE_PORT");
        normalized = normalized.replace("Port: " + port, "Port: $DEBUGBRIDGE_PORT");
        normalized = normalized.replace("port " + port, "port $DEBUGBRIDGE_PORT");
        return normalized.equals(Integer.toString(port)) ? "$DEBUGBRIDGE_PORT" : normalized;
    }

    private static Map<String, Object> replaceBridgePort(Map<String, Object> request, int port) {
        return map(replaceBridgePortValue(request, port));
    }

    private static Object replaceBridgePortValue(Object value, int port) {
        if (value instanceof Map<?, ?> object) {
            var replaced = new LinkedHashMap<String, Object>();
            object.forEach((key, child) -> replaced.put((String) key, replaceBridgePortValue(child, port)));
            return replaced;
        }
        if (value instanceof List<?> array) {
            return array.stream().map(child -> replaceBridgePortValue(child, port)).toList();
        }
        return "$DEBUGBRIDGE_PORT".equals(value) ? port : value;
    }

    private static void assertCatalogCoverage(Map<String, Object> toolsListResponse, List<Scenario> scenarios) {
        Set<String> advertised = toolNames(toolsListResponse);
        Map<String, Set<String>> coveredByKind = new LinkedHashMap<>();
        for (Scenario scenario : scenarios) {
            if (!scenario.request().get("method").equals("tools/call")) {
                continue;
            }
            Map<String, Object> parameters = map(scenario.request().get("params"));
            String name = Objects.toString(parameters.get("name"));
            if (advertised.contains(name)) {
                coveredByKind.computeIfAbsent(scenario.kind(), ignored -> new LinkedHashSet<>()).add(name);
            }
        }
        Set<String> staticCovered = coveredByKind.getOrDefault("static", Set.of());
        Set<String> runtimeCovered = coveredByKind.getOrDefault("runtime", Set.of());
        var expectedCatalog = new LinkedHashSet<>(STATIC_TOOLS);
        expectedCatalog.addAll(RUNTIME_TOOLS);
        var allCovered = new LinkedHashSet<>(staticCovered);
        allCovered.addAll(runtimeCovered);

        assertEquals(expectedCatalog, advertised, "The parity catalog fixture must expose every production tool, including opt-in developer tools");
        assertEquals(STATIC_TOOLS, staticCovered, "Every static handler must cross the stdio process boundary");
        assertEquals(RUNTIME_TOOLS, runtimeCovered, "Every runtime handler must cross the stdio process boundary");
        assertEquals(advertised, allCovered, "The parity corpus must fail when a newly advertised tool lacks a scenario");
    }

    private static Set<String> toolNames(Map<String, Object> response) {
        Object tools = map(response.get("result")).get("tools");
        List<?> toolList = assertInstanceOf(List.class, tools, "tools/list result must contain an array");
        var names = new LinkedHashSet<String>();
        for (Object tool : toolList) {
            assertTrue(names.add(Objects.toString(map(tool).get("name"))), "tools/list must not contain duplicate names");
        }
        return names;
    }

    private static void assertStaticProcessCoverage(List<Scenario> scenarios) {
        Map<String, Set<StaticOutcome>> outcomesByTool = new LinkedHashMap<>();
        for (Scenario scenario : scenarios) {
            if (!scenario.kind().equals("static")) {
                continue;
            }
            Map<String, Object> parameters = map(scenario.request().get("params"));
            String tool = Objects.toString(parameters.get("name"));
            outcomesByTool.computeIfAbsent(tool, ignored -> new LinkedHashSet<>()).add(scenario.staticOutcome());
        }
        assertEquals(REQUIRED_PROCESS_STATIC_OUTCOMES, outcomesByTool, "Static process parity must prove every applicable response outcome for all eight handlers");
    }

    private static void assertStaticOutcome(Scenario scenario, Map<String, Object> response, String server) {
        Map<String, Object> result = map(response.get("result"));
        boolean protocolError = Boolean.TRUE.equals(result.get("isError"));
        String text = toolText(response);
        boolean unavailable = text.startsWith("Version ") && text.contains(" not initialized.");
        boolean empty = text.startsWith("No ") || text.startsWith("Class not found:") || text.startsWith("Method \"");
        boolean truncated = text.contains(" (showing first ") && text.contains("pass a larger `limit` to see more");

        switch (scenario.staticOutcome()) {
            case ERROR ->
                    assertTrue(protocolError || unavailable, () -> server + " response for " + scenario.label() + " did not exercise a real error outcome: " + text);
            case EMPTY ->
                    assertTrue(!protocolError && !unavailable && empty && !truncated, () -> server + " response for " + scenario.label() + " was not an empty result: " + text);
            case TRUNCATED ->
                    assertTrue(!protocolError && !unavailable && !empty && truncated, () -> server + " response for " + scenario.label() + " was not a truncated result: " + text);
            case SUCCESS ->
                    assertTrue(!protocolError && !unavailable && !empty && !truncated && !text.isBlank(), () -> server + " response for " + scenario.label() + " was not a nonempty, nontruncated success: " + text);
        }
    }

    private static Path writeReport(Scenario scenario, Map<String, Object> rawNode, Map<String, Object> rawJava, Map<String, Object> normalizedNode, Map<String, Object> normalizedJava, String difference) throws IOException {
        Path reportDirectory = Path.of("build", "reports", "parity").toAbsolutePath().normalize();
        Files.createDirectories(reportDirectory);
        Path report = reportDirectory.resolve(scenario.label() + ".json");
        var contents = new LinkedHashMap<String, Object>();
        contents.put("label", scenario.label());
        contents.put("kind", scenario.kind());
        contents.put("request", scenario.request());
        contents.put("firstDifference", difference);
        contents.put("nodeRaw", rawNode);
        contents.put("javaRaw", rawJava);
        contents.put("nodeNormalized", normalizedNode);
        contents.put("javaNormalized", normalizedJava);
        Files.writeString(report, MAPPER.writeValueAsString(contents) + System.lineSeparator(), StandardCharsets.UTF_8);
        return report;
    }

    private static String firstDifference(Object node, Object java, String pointer) {
        if (node instanceof Map<?, ?> nodeMap && java instanceof Map<?, ?> javaMap) {
            if (!nodeMap.keySet().equals(javaMap.keySet())) {
                return pointer + "/<keys>";
            }
            for (Object key : nodeMap.keySet()) {
                String difference = firstDifference(nodeMap.get(key), javaMap.get(key), pointer + "/" + escapePointer(key.toString()));
                if (difference != null) {
                    return difference;
                }
            }
            return null;
        }
        if (node instanceof List<?> nodeList && java instanceof List<?> javaList) {
            if (nodeList.size() != javaList.size()) {
                return pointer + "/<length>";
            }
            for (int index = 0; index < nodeList.size(); index++) {
                String difference = firstDifference(nodeList.get(index), javaList.get(index), pointer + "/" + index);
                if (difference != null) {
                    return difference;
                }
            }
            return null;
        }
        return Objects.equals(node, java) ? null : pointer;
    }

    private static String escapePointer(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static List<Scenario> scenarios() throws IOException {
        return readParityRequests().stream().map(document -> new Scenario(Objects.toString(document.get("label")), Objects.toString(document.get("kind")), Objects.toString(document.get("comparison"), "exact"), StaticOutcome.from(document.get("outcome")), map(document.get("request")))).toList();
    }

    private static List<Map<String, Object>> readParityRequests() throws IOException {
        String resource = "contracts/parity/requests.jsonl";
        try (InputStream input = DifferentialMcpTest.class.getClassLoader().getResourceAsStream(resource)) {
            Objects.requireNonNull(input, "Missing test resource " + resource);
            List<Map<String, Object>> documents = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        documents.add(MAPPER.readValue(line, MAP_TYPE));
                    } catch (RuntimeException exception) {
                        throw new IOException("Invalid JSON value at line " + lineNumber + " in " + resource, exception);
                    }
                }
            }
            return List.copyOf(documents);
        }
    }

    private static Map<String, Object> map(Object value) {
        return MAPPER.convertValue(value, MAP_TYPE);
    }

    private enum StaticOutcome {
        SUCCESS, ERROR, EMPTY, TRUNCATED;

        private static StaticOutcome from(Object value) {
            return value == null ? null : valueOf(Objects.toString(value).toUpperCase(Locale.ROOT));
        }
    }

    private record Scenario(String label, String kind, String comparison, StaticOutcome staticOutcome, Map<String, Object> request) {
        private Scenario {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(comparison, "comparison");
            if (!comparison.equals("exact") && !comparison.equals("find_refs_descriptors") && !comparison.equals("java_launcher") && !comparison.equals("sdk_input_validation") && !comparison.equals("pinned_guide_links") && !comparison.equals("reviewed_guide")) {
                throw new IllegalArgumentException("Unknown parity comparison mode: " + comparison);
            }
            if (kind.equals("static")) {
                Objects.requireNonNull(staticOutcome, "Static parity scenarios require an outcome: " + label);
            }
            else if (staticOutcome != null) {
                throw new IllegalArgumentException("Only static parity scenarios may declare an outcome: " + label);
            }
            request = Map.copyOf(Objects.requireNonNull(request, "request"));
        }
    }
}
