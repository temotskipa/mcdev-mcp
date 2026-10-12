package dev.mcdevmcp.storage.h2;

import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import reactor.util.annotation.NonNull;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

public final class IndexCleaner {
    private final PlatformPaths paths;
    private final DatabaseFileOperations files;

    public IndexCleaner(PlatformPaths paths) {
        this(paths, Files::move);
    }

    IndexCleaner(PlatformPaths paths, DatabaseFileOperations files) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.files = Objects.requireNonNull(files, "files");
    }

    private static void rejectSymbolicLink(Path path, String description) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Refusing to clean a symbolic link " + description + ": " + path);
        }
    }

    private void deleteContained(Path root, Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("Refusing to delete path outside index root: " + candidate);
        }
        files.delete(normalized);
    }

    private void deleteArtifacts(Path root, Path database, Path lockPath) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            @NonNull
            public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attributes) throws IOException {
                Path normalized = file.toAbsolutePath().normalize();
                if (!normalized.equals(lockPath) && !normalized.equals(database)) {
                    deleteContained(root, normalized);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            @NonNull
            public FileVisitResult postVisitDirectory(@NonNull Path directory, IOException exception) throws IOException {
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

    public void cleanIndex(MinecraftVersion version) throws IOException {
        Path root = paths.indexRoot(version).toAbsolutePath().normalize();
        if (!root.startsWith(paths.cacheRoot().toAbsolutePath().normalize())) {
            throw new IOException("Refusing to clean index outside cache root: " + root);
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path database = paths.symbolDatabase(version).toAbsolutePath().normalize();
        if (!database.startsWith(root)) {
            throw new IOException("Refusing to clean symbol database outside index root: " + database);
        }
        rejectSymbolicLink(root, "version index root");
        rejectSymbolicLink(database, "symbol database");
        Path lockPath = database.resolveSibling(database.getFileName() + ".lock");
        try (var databaseLock = DatabaseLock.write(database, AtomicH2Database.WRITE_LOCK_TIMEOUT)) {
            if (!databaseLock.isHeld()) {
                throw new IOException("Failed to acquire exclusive database lock for index cleanup");
            }
            deleteArtifacts(root, database, lockPath);
            if (Files.exists(database, LinkOption.NOFOLLOW_LINKS)) {
                deleteContained(root, database);
            }
        }
    }
}
