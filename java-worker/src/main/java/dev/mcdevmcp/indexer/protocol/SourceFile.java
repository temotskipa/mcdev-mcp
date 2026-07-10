package dev.mcdevmcp.indexer.protocol;

public record SourceFile(String path) {
    public SourceFile {
        if (path == null) {
            throw new IllegalArgumentException("missing file path");
        }
    }

    public String normalizedPath() {
        return path.replace('\\', '/');
    }
}