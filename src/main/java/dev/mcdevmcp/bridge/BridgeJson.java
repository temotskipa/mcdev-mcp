package dev.mcdevmcp.bridge;

import dev.mcdevmcp.support.JsonValues;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class BridgeJson {
    private final McpJsonMapper mapper;

    public BridgeJson(McpJsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    McpJsonMapper mapper() {
        return mapper;
    }

    public String writeRequest(BridgeRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", request.id());
        envelope.put("type", request.endpoint().wireName());
        envelope.put("payload", request.payload());
        try {
            return mapper.writeValueAsString(envelope);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to serialize DebugBridge request " + request.id(), exception);
        }
    }

    public BridgeResponse readResponse(String message) {
        if (message == null || message.length() > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("DebugBridge response is missing or exceeds the wire limit");
        }
        try {
            BridgeWireResponse wire = mapper.readValue(message, BridgeWireResponse.class);
            if (wire == null || wire.id() == null || wire.id().isBlank()) {
                throw new IllegalArgumentException("DebugBridge response is missing required id");
            }
            if (wire.success() == null) {
                throw new IllegalArgumentException("DebugBridge response " + BridgePayloadValidator.safeDisplay(wire.id()) + " is missing required success");
            }
            return new BridgeResponse(wire.id(), wire.success(), JsonValues.freeze(wire.result()), wire.output(), wire.error());
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalArgumentException("Malformed DebugBridge response", exception);
        }
    }
}
