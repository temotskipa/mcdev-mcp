package dev.mcdevmcp.storage;

import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

public final class IndexCleaner {
    private final PlatformPaths paths;
    
    public IndexCleaner(PlatformPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    private static void deleteContained(Path root, Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("Refusing to delete path outside index root: " + candidate);
        }
        Files.delete(normalized);
    }
    
    private static void rejectH2Locks(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            Path lock = paths.filter(path -> path.getFileName().toString().endsWith(".lock.db")).findFirst().orElse(null);
            if (lock != null) {
                throw new IOException("Refusing to clean while an H2 lock companion exists: " + lock);
            }
        }
    }

    public void cleanIndex(MinecraftVersion version) throws IOException {
        Path root = paths.indexRoot(version).toAbsolutePath().normalize();
        if (!root.startsWith(paths.cacheRoot().toAbsolutePath().normalize())) {
            throw new IOException("Refusing to clean index outside cache root: " + root);
        }
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path lockPath = paths.symbolDatabase(version).toAbsolutePath().normalize().resolveSibling(paths.symbolDatabase(version).getFileName() + ".lock");
        try (var databaseLock = DatabaseLock.write(paths.symbolDatabase(version), AtomicH2Database.WRITE_LOCK_TIMEOUT)) {
            if (!databaseLock.isHeld()) {
                throw new IOException("Failed to acquire exclusive database lock for index cleanup");
            }
            rejectH2Locks(root);
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                @SuppressWarnings("NullableProblems")
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (!file.toAbsolutePath().normalize().equals(lockPath)) {
                        deleteContained(root, file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                @SuppressWarnings("NullableProblems")
                public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    if (!directory.equals(root)) {
                        deleteContained(root, directory);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
