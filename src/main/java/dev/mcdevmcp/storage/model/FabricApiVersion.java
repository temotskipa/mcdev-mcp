package dev.mcdevmcp.storage.model;

import java.util.Objects;

public record FabricApiVersion(String value) {
    public FabricApiVersion {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Fabric API version must not be blank");
        }
    }
}
