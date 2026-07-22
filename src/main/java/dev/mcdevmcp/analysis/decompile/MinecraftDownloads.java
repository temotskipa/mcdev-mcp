package dev.mcdevmcp.analysis.decompile;

import java.util.Objects;

public record MinecraftDownloads(DownloadArtifact client, DownloadArtifact clientMappings, OfficialUnobfuscatedClient officialUnobfuscatedClient) {
    public MinecraftDownloads {
        Objects.requireNonNull(client, "client");
    }
}
