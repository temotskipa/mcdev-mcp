package dev.mcdevmcp.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;

public final class AtomicSqliteDatabase {
    public static final Duration WRITE_LOCK_TIMEOUT = Duration.ofSeconds(30);

    public <T> T rebuild(Path target, Duration lockTimeout, SqliteBuilder<T> builder, SqliteValidator validator) throws IOException, SQLException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(lockTimeout, "lockTimeout");
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(validator, "validator");
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Files.createDirectories(normalizedTarget.getParent());
        try (var databaseLock = DatabaseLock.write(normalizedTarget, lockTimeout)) {
            if (!databaseLock.isHeld()) {
                throw new IOException("Failed to acquire exclusive database lock");
            }
            restoreBackupWhenTargetMissing(normalizedTarget);
            Path temporary = temporaryPath(normalizedTarget);
            Files.deleteIfExists(temporary);
            try {
                T result = buildTemporaryDatabase(temporary, builder, validator);
                verifyNoSidecars(temporary);
                promote(temporary, normalizedTarget, validator);
                return result;
            } catch (IOException | SQLException exception) {
                Files.deleteIfExists(temporary);
                throw exception;
            } catch (Exception exception) {
                Files.deleteIfExists(temporary);
                throw new SQLException("SQLite database rebuild failed", exception);
            }
        }
    }

    private static <T> T buildTemporaryDatabase(Path temporary, SqliteBuilder<T> builder, SqliteValidator validator) throws Exception {
        try (Connection connection = DriverManager.getConnection(url(temporary))) {
            configureWriter(connection);
            connection.setAutoCommit(false);
            try {
                T result = builder.build(connection);
                validator.validate(connection);
                SymbolSchema.validateForeignKeys(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void configureWriter(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = DELETE");
            statement.execute("PRAGMA synchronous = FULL");
            statement.execute("PRAGMA foreign_keys = ON");
        }
    }

    private static void promote(Path temporary, Path target, SqliteValidator validator) throws IOException, SQLException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            promoteWithBackup(temporary, target, validator, exception);
        }
    }

    private static void promoteWithBackup(Path temporary, Path target, SqliteValidator validator, AtomicMoveNotSupportedException atomicFailure) throws IOException, SQLException {
        Path backup = backupPath(target);
        boolean backupCreated = false;
        try {
            Files.deleteIfExists(backup);
            if (Files.exists(target)) {
                Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
                backupCreated = true;
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            validatePromotedDatabase(target, validator);
            if (backupCreated) {
                Files.deleteIfExists(backup);
            }
        } catch (IOException | SQLException exception) {
            IOException restoreFailure = restoreBackup(target, backup, backupCreated);
            if (restoreFailure != null) {
                exception.addSuppressed(restoreFailure);
            }
            exception.addSuppressed(atomicFailure);
            throw exception;
        }
    }

    private static void validatePromotedDatabase(Path target, SqliteValidator validator) throws IOException, SQLException {
        try (Connection connection = DriverManager.getConnection(url(target))) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }
            try {
                validator.validate(connection);
                SymbolSchema.validateForeignKeys(connection);
            } catch (SQLException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new SQLException("Promoted SQLite database validation failed", exception);
            }
        }
    }

    private static IOException restoreBackup(Path target, Path backup, boolean backupCreated) {
        if (!backupCreated) {
            return null;
        }
        try {
            Files.deleteIfExists(target);
            if (Files.exists(backup)) {
                Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        } catch (IOException exception) {
            return exception;
        }
    }

    private static void restoreBackupWhenTargetMissing(Path target) throws IOException {
        Path backup = backupPath(target);
        if (!Files.exists(target) && Files.exists(backup)) {
            Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void verifyNoSidecars(Path database) throws IOException {
        for (String suffix : new String[]{"-journal", "-wal", "-shm"}) {
            if (Files.exists(database.resolveSibling(database.getFileName() + suffix))) {
                throw new IOException("SQLite sidecar remained after closing temporary database: " + database + suffix);
            }
        }
    }

    private static Path temporaryPath(Path target) {
        long processId = ProcessHandle.current().pid();
        return target.resolveSibling(target.getFileName() + "." + processId + ".tmp");
    }

    private static Path backupPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".bak");
    }

    static String url(Path database) {
        return "jdbc:sqlite:" + database.toAbsolutePath();
    }
}
