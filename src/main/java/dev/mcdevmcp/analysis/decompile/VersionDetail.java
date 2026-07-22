package dev.mcdevmcp.analysis.decompile;

import java.util.Map;
import java.util.Objects;

public record VersionDetail(Map<String, DownloadWire> downloads) {
    public VersionDetail {
        downloads = Map.copyOf(Objects.requireNonNull(downloads, "downloads"));
    }
}
