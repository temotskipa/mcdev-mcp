package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeResponse;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

final class RuntimeContractFixtures {
    private RuntimeContractFixtures() {
    }

    static BridgeResponse status(String requestId) {
        var result = new LinkedHashMap<String, Object>();
        result.put("version", "1.21.11");
        result.put("mappingStatus", "mojang");
        result.put("obfuscated", true);
        result.put("refs", 7);
        result.put("gameDir", "C:\\Game");
        result.put("latestLog", "C:\\Game\\logs\\latest.log");
        result.put("sessionControlEnabled", true);
        return new BridgeResponse(requestId, true, true, result, null, null);
    }

    static <T> List<T> load(McpJsonMapper mapper, String resource, Class<T> type) throws IOException {
        try (var input = RuntimeContractFixtures.class.getClassLoader().getResourceAsStream(resource)) {
            String contents = new String(Objects.requireNonNull(input, resource).readAllBytes(), StandardCharsets.UTF_8);
            List<T> documents = new ArrayList<>();
            int start = -1;
            int depth = 0;
            boolean quoted = false;
            boolean escaped = false;
            for (int index = 0; index < contents.length(); index++) {
                char character = contents.charAt(index);
                if (start < 0) {
                    if (Character.isWhitespace(character)) {
                        continue;
                    }
                    if (character != '{') {
                        throw new IOException("Expected a JSON object at offset " + index + " in " + resource);
                    }
                    start = index;
                }
                if (quoted) {
                    if (escaped) {
                        escaped = false;
                    }
                    else if (character == '\\') {
                        escaped = true;
                    }
                    else if (character == '"') {
                        quoted = false;
                    }
                    continue;
                }
                if (character == '"') {
                    quoted = true;
                }
                else if (character == '{' || character == '[') {
                    depth++;
                }
                else if (character == '}' || character == ']') {
                    depth--;
                    if (depth == 0) {
                        documents.add(mapper.readValue(contents.substring(start, index + 1), type));
                        start = -1;
                    }
                    else if (depth < 0) {
                        throw new IOException("Unbalanced JSON at offset " + index + " in " + resource);
                    }
                }
            }
            if (start >= 0) {
                throw new IOException("Incomplete JSON document in " + resource);
            }
            return List.copyOf(documents);
        }
    }
}
