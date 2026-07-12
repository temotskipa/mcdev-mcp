package dev.mcdevmcp.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;

public final class AtomicH2Database {
    public static final Duration WRITE_LOCK_TIMEOUT = Duration.ofSeconds(30);
    
    private final DatabaseMoveStrategy moves;
    
    public AtomicH2Database() {
        this(Files::move);
    }
    
    AtomicH2Database(DatabaseMoveStrategy moves) {
        this.moves = Objects.requireNonNull(moves, "moves");
    }
    
    private static <T> T buildTemporaryDatabase(Path temporary, DatabaseBuilder<T> builder, DatabaseValidator validator) throws Exception {
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.writer(temporary))) {
            connection.setAutoCommit(false);
            try {
                T result = builder.build(connection);
                validator.validate(connection);
                SymbolSchema.validate(connection);
                connection.commit();
                try (Statement statement = connection.createStatement()) {
                    statement.execute(checkpointSql());
                }
                return result;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }
    
    private static void verifyClosedDatabase(Path database, DatabaseValidator validator) throws IOException, SQLException {
        verifyNoCompanions(database);
        force(database);
        validatePromotedDatabase(database, validator);
        verifyNoCompanions(database);
    }
    
    private static void force(Path database) throws IOException {
        try (FileChannel channel = FileChannel.open(database, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
    
    private static String checkpointSql() {
        return "CHECKPOINT SYNC";
    }
    
    private static void validatePromotedDatabase(Path target, DatabaseValidator validator) throws IOException, SQLException {
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.reader(target))) {
            try {
                validator.validate(connection);
                SymbolSchema.validate(connection);
            } catch (SQLException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new SQLException("Promoted H2 database validation failed", exception);
            }
        }
    }
    
    private static void resolveBackup(Path target, DatabaseValidator validator) throws IOException {
        Path backup = backupPath(target);
        if (!Files.exists(backup)) {
            return;
        }
        if (!Files.exists(target)) {
            Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        try {
            validatePromotedDatabase(target, validator);
        } catch (IOException | SQLException exception) {
            throw new IOException("Both H2 target and backup exist and the target is invalid; preserve and inspect " + target + " and " + backup, exception);
        }
        Files.delete(backup);
    }
    
    private static void restorePrePromotionState(Path target, Path backup, boolean backupCreated, boolean temporaryPromoted, Exception originalFailure) {
        try {
            if (temporaryPromoted) {
                Files.deleteIfExists(target);
            }
            if (backupCreated && Files.exists(backup)) {
                if (Files.exists(target)) {
                    throw new IOException("Preserving backup because target state is uncertain after failed promotion: " + target);
                }
                Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException restoreFailure) {
            originalFailure.addSuppressed(restoreFailure);
        }
    }
    
    private static Path temporaryPath(Path target) {
        Path base = H2DatabaseUrls.basePath(target);
        return base.resolveSibling(base.getFileName() + "." + ProcessHandle.current().pid() + ".tmp.mv.db");
    }
    
    private static Path backupPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".bak");
    }
    
    private static void rejectActiveCompanion(Path database) throws IOException {
        Path activeLock = H2DatabaseUrls.basePath(database).resolveSibling(H2DatabaseUrls.basePath(database).getFileName() + ".lock.db");
        if (Files.exists(activeLock)) {
            throw new IOException("Refusing to rebuild while an H2 lock companion exists: " + activeLock);
        }
    }
    
    private static void clearStaleCompanions(Path database) throws IOException {
        Path base = H2DatabaseUrls.basePath(database);
        Files.deleteIfExists(base.resolveSibling(base.getFileName() + ".newFile"));
        Files.deleteIfExists(base.resolveSibling(base.getFileName() + ".tempFile"));
        Files.deleteIfExists(base.resolveSibling(base.getFileName() + ".trace.db"));
        Files.deleteIfExists(base.resolveSibling(base.getFileName() + ".trace.db.old"));
        for (Path numberedCompanion : numberedCompanions(base)) {
            Files.delete(numberedCompanion);
        }
    }
    
    private static void deleteTemporaryArtifacts(Path temporary) throws IOException {
        Files.deleteIfExists(temporary);
        clearStaleCompanions(temporary);
    }
    
    private static void verifyNoCompanions(Path database) throws IOException {
        Path base = H2DatabaseUrls.basePath(database);
        String name = base.getFileName().toString();
        String[] suffixes = {".newFile", ".tempFile", ".lock.db", ".trace.db", ".trace.db.old"};
        for (String suffix : suffixes) {
            Path companion = base.resolveSibling(name + suffix);
            if (Files.exists(companion)) {
                throw new IOException("H2 companion remained after closing database: " + companion);
            }
        }
        var numbered = numberedCompanions(base);
        if (!numbered.isEmpty()) {
            throw new IOException("H2 companion remained after closing database: " + numbered.getFirst());
        }
    }
    
    private static java.util.List<Path> numberedCompanions(Path base) throws IOException {
        String pattern = java.util.regex.Pattern.quote(base.getFileName().toString()) + "\\.\\d+\\.temp\\.db";
        try (var siblings = Files.list(base.getParent())) {
            return siblings.filter(path -> path.getFileName().toString().matches(pattern)).toList();
        }
    }
    
    public <T> T rebuild(Path target, Duration lockTimeout, DatabaseBuilder<T> builder, DatabaseValidator validator) throws IOException, SQLException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(lockTimeout, "lockTimeout");
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(validator, "validator");
        Path normalizedTarget = target.toAbsolutePath().normalize();
        H2DatabaseUrls.basePath(normalizedTarget);
        Files.createDirectories(normalizedTarget.getParent());
        try (var databaseLock = DatabaseLock.write(normalizedTarget, lockTimeout)) {
            if (!databaseLock.isHeld()) {
                throw new IOException("Failed to acquire exclusive database lock");
            }
            resolveBackup(normalizedTarget, validator);
            rejectActiveCompanion(normalizedTarget);
            clearStaleCompanions(normalizedTarget);
            Path temporary = temporaryPath(normalizedTarget);
            deleteTemporaryArtifacts(temporary);
            try {
                T result = buildTemporaryDatabase(temporary, builder, validator);
                verifyClosedDatabase(temporary, validator);
                promote(temporary, normalizedTarget, validator);
                return result;
            } catch (IOException | SQLException exception) {
                deleteTemporaryArtifacts(temporary);
                throw exception;
            } catch (Exception exception) {
                deleteTemporaryArtifacts(temporary);
                throw new SQLException("H2 database rebuild failed", exception);
            }
        }
    }
    
    private void promote(Path temporary, Path target, DatabaseValidator validator) throws IOException, SQLException {
        try {
            moves.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            verifyNoCompanions(temporary);
            promoteWithBackup(temporary, target, validator, exception);
        }
    }
    
    private void promoteWithBackup(Path temporary, Path target, DatabaseValidator validator, AtomicMoveNotSupportedException atomicFailure) throws IOException, SQLException {
        Path backup = backupPath(target);
        boolean backupCreated = false;
        boolean temporaryPromoted = false;
        try {
            Files.deleteIfExists(backup);
            if (Files.exists(target)) {
                moves.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
                backupCreated = true;
            }
            moves.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            temporaryPromoted = true;
            validatePromotedDatabase(target, validator);
            if (backupCreated) {
                Files.deleteIfExists(backup);
            }
        } catch (IOException | SQLException exception) {
            restorePrePromotionState(target, backup, backupCreated, temporaryPromoted, exception);
            exception.addSuppressed(atomicFailure);
            throw exception;
        }
    }
}
