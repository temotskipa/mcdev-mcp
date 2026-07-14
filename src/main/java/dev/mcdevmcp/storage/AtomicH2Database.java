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
    
    private final DatabaseFileOperations files;
    
    public AtomicH2Database() {
        this(Files::move);
    }
    
    AtomicH2Database(DatabaseFileOperations files) {
        this.files = Objects.requireNonNull(files, "files");
    }
    
    private static <T> T buildTemporaryDatabase(Path temporary, DatabaseBuilder<T> builder, DatabaseValidator validator) throws Exception {
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.writer(temporary))) {
            connection.setAutoCommit(false);
            try {
                T result = builder.build(connection);
                validator.validate(connection);
                connection.commit();
                try (Statement statement = connection.createStatement()) {
                    statement.execute(checkpointSql());
                }
                return result;
            } catch (Exception exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
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
            } catch (SQLException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new SQLException("Promoted H2 database validation failed", exception);
            }
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
        rejectActiveCompanion(temporary);
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
    
    private void resolveBackup(Path target, DatabaseValidator validator) throws IOException {
        Path backup = backupPath(target);
        if (!Files.exists(backup)) {
            return;
        }
        if (!Files.exists(target)) {
            files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        try {
            validatePromotedDatabase(target, validator);
        } catch (IOException | SQLException exception) {
            throw new IOException("Both H2 target and backup exist and the target is invalid; preserve and inspect " + target + " and " + backup, exception);
        }
        Files.delete(backup);
    }
    
    private void restorePrePromotionState(Path target, Path backup, DatabasePromotionPhase phase, boolean originalTargetExisted, Exception originalFailure) {
        boolean targetExists = Files.exists(target);
        boolean backupExists = Files.exists(backup);
        if (phase == DatabasePromotionPhase.BACKING_UP_TARGET) {
            if (targetExists) {
                return;
            }
            if (backupExists) {
                restoreBackup(target, backup, originalFailure);
                return;
            }
            originalFailure.addSuppressed(new IOException("Neither target nor backup remains after failed backup move: " + target + " and " + backup));
            return;
        }
        
        if (phase != DatabasePromotionPhase.PROMOTING_TEMPORARY) {
            return;
        }
        if (targetExists) {
            try {
                files.delete(target);
            } catch (IOException exception) {
                originalFailure.addSuppressed(new IOException("Unable to remove uncertain promoted target; preserving observed state for " + target + " and " + backup, exception));
                return;
            }
        }
        if (backupExists) {
            restoreBackup(target, backup, originalFailure);
        }
        else if (originalTargetExisted) {
            originalFailure.addSuppressed(new IOException("Backup missing after failed temporary promotion: " + backup));
        }
    }
    
    private void restoreBackup(Path target, Path backup, Exception originalFailure) {
        DatabasePromotionPhase phase = DatabasePromotionPhase.RESTORING_BACKUP;
        try {
            files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            boolean targetExists = Files.exists(target);
            boolean backupExists = Files.exists(backup);
            String state = "target=" + targetExists + ", backup=" + backupExists + ", phase=" + phase;
            originalFailure.addSuppressed(new IOException("Unable to restore backup; preserving observed state for " + target + " and " + backup + " (" + state + ")", exception));
        }
    }
    
    public <T> T rebuild(Path target, Duration lockTimeout, DatabaseBuilder<T> builder, DatabaseValidator validator) throws IOException, SQLException {
        return rebuild(target, lockTimeout, builder, validator, validator);
    }

    public <T> T rebuild(Path target, Duration lockTimeout, DatabaseBuilder<T> builder, DatabaseValidator existingTargetValidator, DatabaseValidator candidateValidator) throws IOException, SQLException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(lockTimeout, "lockTimeout");
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(existingTargetValidator, "existingTargetValidator");
        Objects.requireNonNull(candidateValidator, "candidateValidator");
        Path normalizedTarget = target.toAbsolutePath().normalize();
        H2DatabaseUrls.basePath(normalizedTarget);
        Files.createDirectories(normalizedTarget.getParent());
        try (var databaseLock = DatabaseLock.write(normalizedTarget, lockTimeout)) {
            if (!databaseLock.isHeld()) {
                throw new IOException("Failed to acquire exclusive database lock");
            }
            resolveBackup(normalizedTarget, existingTargetValidator);
            rejectActiveCompanion(normalizedTarget);
            clearStaleCompanions(normalizedTarget);
            Path temporary = temporaryPath(normalizedTarget);
            deleteTemporaryArtifacts(temporary);
            try {
                T result = buildTemporaryDatabase(temporary, builder, candidateValidator);
                verifyClosedDatabase(temporary, candidateValidator);
                promote(temporary, normalizedTarget, candidateValidator);
                return result;
            } catch (Exception exception) {
                try {
                    deleteTemporaryArtifacts(temporary);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                if (exception instanceof IOException ioException) {
                    throw ioException;
                }
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException("H2 database rebuild failed", exception);
            }
        }
    }
    
    private void promote(Path temporary, Path target, DatabaseValidator validator) throws IOException, SQLException {
        try {
            files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            verifyNoCompanions(temporary);
            promoteWithBackup(temporary, target, validator, exception);
        }
    }
    
    private void promoteWithBackup(Path temporary, Path target, DatabaseValidator validator, AtomicMoveNotSupportedException atomicFailure) throws IOException, SQLException {
        Path backup = backupPath(target);
        boolean originalTargetExisted = Files.exists(target);
        DatabasePromotionPhase phase = null;
        try {
            Files.deleteIfExists(backup);
            if (originalTargetExisted) {
                phase = DatabasePromotionPhase.BACKING_UP_TARGET;
                files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            phase = DatabasePromotionPhase.PROMOTING_TEMPORARY;
            files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            validatePromotedDatabase(target, validator);
            if (Files.exists(backup)) {
                Files.deleteIfExists(backup);
            }
        } catch (IOException | SQLException exception) {
            restorePrePromotionState(target, backup, phase, originalTargetExisted, exception);
            exception.addSuppressed(atomicFailure);
            throw exception;
        }
    }
}
