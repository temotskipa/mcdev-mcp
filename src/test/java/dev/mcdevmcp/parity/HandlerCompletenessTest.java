package dev.mcdevmcp.parity;

import dev.mcdevmcp.bridge.BridgeTestHarness;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.ToolMetadata;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.JsonResourceReader;
import dev.mcdevmcp.tools.runtime.RuntimeToolModule;
import dev.mcdevmcp.tools.statictool.StaticToolModule;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HandlerCompletenessTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void everyMetadataEntryHasExactlyOneBoundHandlerIncludingEnvironmentGatedTools() {
        var environment = new AppEnvironment(Map.of("LOCALAPPDATA", temporaryDirectory.toString(), "XDG_CACHE_HOME", temporaryDirectory.toString(), "MCDEV_SESSION_LOG_DIR", temporaryDirectory.resolve("session-logs").toString(), "MCDEV_RUN_COMMAND", "true"));
        var handlers = new LinkedHashMap<String, ToolBinding<?>>();

        StaticToolModule.handlers(PlatformPaths.forEnvironment("Linux", environment.values(), temporaryDirectory)).forEach((name, binding) -> assertNull(handlers.put(name, binding), () -> "duplicate static handler: " + name));
        try (var bridge = new BridgeTestHarness(MAPPER, environment, (_, _) -> new CompletableFuture<>())) {
            RuntimeToolModule.handlers(bridge.session(), MAPPER, environment).forEach((name, binding) -> assertNull(handlers.put(name, binding), () -> "duplicate runtime handler: " + name));
        }

        ToolMetadata[] metadata = new JsonResourceReader(MAPPER).read("/mcp/tools.json", ToolMetadata[].class);
        Set<String> metadataNames = java.util.Arrays.stream(metadata).map(ToolMetadata::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertEquals(metadata.length, metadataNames.size(), "tool metadata must not contain duplicate names");
        assertEquals(metadataNames, handlers.keySet());
        assertEquals(metadata.length, ToolCatalog.load(environment, handlers, MAPPER).enabledDefinitions().size());
    }
}
