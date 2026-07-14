package dev.mcdevmcp.storage.model;

import java.nio.file.Path;
import java.util.Objects;

public record FabricApiVersion(String value) {
    public FabricApiVersion {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Fabric API version must not be blank");
        }
        Path component = Path.of(value);
        if (value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\") || component.isAbsolute() || component.getNameCount() != 1) {
            throw new IllegalArgumentException("Fabric API version must be a safe single path component: " + value);
        }
    }
}
