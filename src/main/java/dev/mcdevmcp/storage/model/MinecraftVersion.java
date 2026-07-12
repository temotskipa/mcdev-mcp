package dev.mcdevmcp.storage.model;

import java.nio.file.Path;
import java.util.Objects;

public record MinecraftVersion(String value) {
    public MinecraftVersion {
        Objects.requireNonNull(value, "value");
        Path path = Path.of(value);
        if (value.isBlank() || path.isAbsolute() || path.getNameCount() != 1 || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("Invalid Minecraft version path component: " + value);
        }
    }
}
