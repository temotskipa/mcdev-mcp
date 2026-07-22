package dev.mcdevmcp.bridge;

import java.util.Objects;

public record BridgeRequest(String id, BridgeEndpoint endpoint, Object payload) {
    public BridgeRequest {
        id = Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Bridge request ID must not be blank");
        }
        Objects.requireNonNull(endpoint, "endpoint");
    }
}
