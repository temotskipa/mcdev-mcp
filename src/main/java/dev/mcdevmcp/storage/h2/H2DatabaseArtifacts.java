package dev.mcdevmcp.storage.h2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

value class H2DatabaseArtifacts {
    final Path target;
    final Path temporary;
    final Path backup;

    H2DatabaseArtifacts(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        this.target = normalized;
        temporary = normalized.resolveSibling(normalized.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        backup = normalized.resolveSibling(normalized.getFileName() + ".bak");
    }

    void createTargetDirectory() throws IOException {
        Files.createDirectories(target.getParent());
    }

    void deleteTemporary() throws IOException {
        Files.deleteIfExists(temporary);
    }
}
