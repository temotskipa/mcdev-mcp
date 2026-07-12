package dev.mcdevmcp.storage;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Path;

@FunctionalInterface
interface DatabaseMoveStrategy {
    void move(Path source, Path target, CopyOption... options) throws IOException;
}
