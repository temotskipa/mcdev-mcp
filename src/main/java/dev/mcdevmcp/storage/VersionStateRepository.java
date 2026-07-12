package dev.mcdevmcp.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

public final class VersionStateRepository {
    private final PlatformPaths paths;

    public VersionStateRepository(PlatformPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    public boolean isSqliteReady(String version) {
        Path database = paths.symbolDatabase(version);
        if (!Files.isRegularFile(database)) {
            return false;
        }
        try {
            return new SymbolRepository(database).query(connection -> {
                try (var statement = connection.createStatement(); var results = statement.executeQuery("PRAGMA user_version")) {
                    return results.next() && results.getInt(1) == SymbolSchema.VERSION;
                }
            });
        } catch (IOException | SQLException exception) {
            return false;
        }
    }

    public boolean needsRebuild(String version) {
        return !isSqliteReady(version) && hasLegacyIndex(version);
    }

    public boolean isSourceOnly(String version) {
        return Files.isDirectory(paths.sourceRoot(version)) && !isSqliteReady(version) && !hasLegacyIndex(version);
    }

    public boolean isAbsent(String version) {
        return !isSqliteReady(version) && !hasLegacyIndex(version) && !Files.isDirectory(paths.sourceRoot(version));
    }

    private boolean hasLegacyIndex(String version) {
        Path root = paths.indexRoot(version);
        return Files.isRegularFile(root.resolve("manifest.json"))
                || Files.isDirectory(root.resolve("minecraft"))
                || Files.isDirectory(root.resolve("fabric"));
    }
}
