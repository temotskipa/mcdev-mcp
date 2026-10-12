package dev.mcdevmcp.storage.h2;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;

@FunctionalInterface
interface DatabaseFileOperations {
    void move(Path source, Path target, CopyOption... options) throws IOException;

    default void delete(Path path) throws IOException {
        Files.delete(path);
    }
}
