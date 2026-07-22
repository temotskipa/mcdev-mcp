package dev.mcdevmcp.bridge;

import io.modelcontextprotocol.json.McpJsonDefaults;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class FakeDebugBridge {
    private final Map<String, Object> status;

    FakeDebugBridge(Map<String, Object> status) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("version", "1.21.11");
        values.put("mappingStatus", "mojang");
        values.put("obfuscated", false);
        values.put("refs", 0L);
        values.put("gameDir", gameDirectory());
        values.putAll(status);
        this.status = Map.copyOf(values);
    }

    BridgeClient client() {
        return BridgeClient.testing(new BridgeJson(McpJsonDefaults.getMapper()), request -> CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, request.endpoint().wireName().equals("status") ? status : request.payload(), "", null)));
    }

    static BridgeClient client(BridgeJson json, String version) {
        Map<String, Object> status = Map.of("version", version, "mappingStatus", "mojang", "obfuscated", false, "refs", 0L, "gameDir", gameDirectory());
        return BridgeClient.testing(json, request -> CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, request.endpoint().wireName().equals("status") ? status : request.payload(), "", null)));
    }

    private static String gameDirectory() {
        return Path.of("run").toAbsolutePath().normalize().toString();
    }
}
