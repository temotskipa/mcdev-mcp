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
    
    public void cleanIndex(MinecraftVersion version) throws IOException {
        Path root = paths.indexRoot(version).toAbsolutePath().normalize();
        if (!root.startsWith(paths.cacheRoot().toAbsolutePath().normalize())) {
            throw new IOException("Refusing to clean index outside cache root: " + root);
        }
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            @SuppressWarnings("NullableProblems")
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                deleteContained(root, file);
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            @SuppressWarnings("NullableProblems")
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                deleteContained(root, directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
