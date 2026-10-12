package dev.mcdevmcp.storage.h2;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;

final value class H2DatabaseArtifacts {
    final Path target;
    final Path base;
    final Path temporary;
    final Path backup;

    H2DatabaseArtifacts(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        Path databaseBase = H2DatabaseUrls.basePath(normalized);
        this.target = normalized;
        base = databaseBase;
        temporary = databaseBase.resolveSibling(databaseBase.getFileName() + "." + ProcessHandle.current().pid() + ".tmp.mv.db");
        backup = normalized.resolveSibling(normalized.getFileName() + ".bak");
    }

    static void verifyNoCompanions(Path database) throws IOException {
        Path databaseBase = H2DatabaseUrls.basePath(database);
        String name = databaseBase.getFileName().toString();
        String[] suffixes = {".newFile", ".tempFile", ".lock.db", ".trace.db", ".trace.db.old"};
        for (String suffix : suffixes) {
            Path companion = databaseBase.resolveSibling(name + suffix);
            if (Files.exists(companion)) {
                throw new IOException("H2 companion remained after closing database: " + companion);
            }
        }
        var numbered = numberedCompanions(databaseBase);
        if (!numbered.isEmpty()) {
            throw new IOException("H2 companion remained after closing database: " + numbered.getFirst());
        }
    }

    private static void force(Path database) throws IOException {
        try (FileChannel channel = FileChannel.open(database, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void rejectActiveCompanion(Path database) throws IOException {
        Path databaseBase = H2DatabaseUrls.basePath(database);
        Path activeLock = databaseBase.resolveSibling(databaseBase.getFileName() + ".lock.db");
        if (Files.exists(activeLock)) {
            throw new IOException("Refusing to rebuild while an H2 lock companion exists: " + activeLock);
        }
    }

    private static void clearStaleCompanions(Path database) throws IOException {
        Path databaseBase = H2DatabaseUrls.basePath(database);
        Files.deleteIfExists(databaseBase.resolveSibling(databaseBase.getFileName() + ".newFile"));
        Files.deleteIfExists(databaseBase.resolveSibling(databaseBase.getFileName() + ".tempFile"));
        Files.deleteIfExists(databaseBase.resolveSibling(databaseBase.getFileName() + ".trace.db"));
        Files.deleteIfExists(databaseBase.resolveSibling(databaseBase.getFileName() + ".trace.db.old"));
        for (Path numberedCompanion : numberedCompanions(databaseBase)) {
            Files.delete(numberedCompanion);
        }
    }

    private static List<Path> numberedCompanions(Path base) throws IOException {
        String pattern = Pattern.quote(base.getFileName().toString()) + "\\.\\d+\\.temp\\.db";
        try (var siblings = Files.list(base.getParent())) {
            return siblings.filter(path -> path.getFileName().toString().matches(pattern)).toList();
        }
    }

    void createTargetDirectory() throws IOException {
        Files.createDirectories(target.getParent());
    }

    void rejectActiveTargetCompanion() throws IOException {
        rejectActiveCompanion(target);
    }

    void clearStaleTargetCompanions() throws IOException {
        clearStaleCompanions(target);
    }

    void deleteTemporaryArtifacts() throws IOException {
        rejectActiveCompanion(temporary);
        Files.deleteIfExists(temporary);
        clearStaleCompanions(temporary);
    }

    void verifyClosedTemporaryDatabase(DatabaseValidator validator) throws IOException, SQLException {
        verifyNoCompanions(temporary);
        force(temporary);
        H2DatabasePromotion.validatePromotedDatabase(temporary, validator);
        verifyNoCompanions(temporary);
    }
}
