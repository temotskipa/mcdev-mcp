package dev.mcdevmcp.storage.model;

public record MinecraftVersion(String value) {
    public MinecraftVersion {
        PortablePathComponent.requireValid(value, "Invalid Minecraft version path component: ");
    }
}
