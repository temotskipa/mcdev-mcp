package dev.mcdevmcp.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicSqliteDatabaseTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void restoresBackupWhenTheTargetIsMissingBeforeAFailedRebuild() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.db");
        Path backup = target.resolveSibling("symbols.db.bak");
        createOldMarkerDatabase(backup);

        assertThrows(SQLException.class, () -> new AtomicSqliteDatabase().rebuild(
                target,
                Duration.ofSeconds(1),
                _ -> {
                    throw new SQLException("builder failed");
                },
                AtomicSqliteDatabaseTest::validateMarker));

        assertEquals("old", marker(target));
        assertFalse(Files.exists(backup));
    }

    @Test
    void restoresTheOldDatabaseWhenValidationFails() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.db");
        createOldMarkerDatabase(target);

        assertThrows(SQLException.class, () -> new AtomicSqliteDatabase().rebuild(
                target,
                Duration.ofSeconds(1),
                connection -> {
                    writeMarker(connection, "new");
                    return "new";
                },
                _ -> {
                    throw new SQLException("validation rejected database");
                }));

        assertEquals("old", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.db.bak")));
    }

    @Test
    void promotesValidatedDatabasesWithoutSqliteSidecars() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.db");
        // Stale target-side sidecars from a prior crash must not survive promotion.
        Files.writeString(target.resolveSibling("symbols.db-wal"), "stale");
        Files.writeString(target.resolveSibling("symbols.db-shm"), "stale");
        Files.writeString(target.resolveSibling("symbols.db-journal"), "stale");

        String result = new AtomicSqliteDatabase().rebuild(
                target,
                Duration.ofSeconds(1),
                connection -> {
                    writeMarker(connection, "new");
                    return "built";
                },
                AtomicSqliteDatabaseTest::validateMarker);

        assertEquals("built", result);
        assertEquals("new", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.db-journal")));
        assertFalse(Files.exists(target.resolveSibling("symbols.db-wal")));
        assertFalse(Files.exists(target.resolveSibling("symbols.db-shm")));
    }

    @Test
    void removesInvalidTargetWhenBackupFallbackValidationFailsWithoutPriorTarget() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.db");
        Path temporary = target.resolveSibling("symbols.db.promote.tmp");
        createMarkerDatabase(temporary, "new");

        SQLException exception = assertThrows(SQLException.class, () ->
                AtomicSqliteDatabase.promoteWithBackupForTest(
                        temporary,
                        target,
                        _ -> {
                            throw new SQLException("validation rejected promoted database");
                        }));

        assertTrue(exception.getMessage().contains("validation rejected promoted database")
                || exception.getCause() != null
                || exception.getSuppressed().length > 0);
        assertFalse(Files.exists(target), "invalid promoted target must be removed");
        assertFalse(Files.exists(target.resolveSibling("symbols.db.bak")));
        assertFalse(Files.exists(temporary));
        assertFalse(Files.exists(target.resolveSibling("symbols.db-journal")));
        assertFalse(Files.exists(target.resolveSibling("symbols.db-wal")));
        assertFalse(Files.exists(target.resolveSibling("symbols.db-shm")));
    }

    @Test
    void failedRebuildWithNoPriorTargetLeavesNoDatabaseArtifacts() {
        Path target = temporaryDirectory.resolve("symbols.db");
        Path temporary = target.resolveSibling(target.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");

        assertThrows(SQLException.class, () -> new AtomicSqliteDatabase().rebuild(
                target,
                Duration.ofSeconds(1),
                connection -> {
                    writeMarker(connection, "new");
                    return "new";
                },
                _ -> {
                    throw new SQLException("validation rejected temporary database");
                }));

        assertFalse(Files.exists(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.db.bak")));
        assertFalse(Files.exists(temporary));
    }

    @Test
    void createsTheSymbolSchemaInsideTheAtomicWriterTransaction() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.db");

        new AtomicSqliteDatabase().rebuild(
                target,
                Duration.ofSeconds(1),
                connection -> {
                    SymbolSchema.create(connection, "1.21.5", temporaryDirectory.resolve("client"), "c".repeat(64), java.time.Instant.now());
                    SymbolSchema.createIndexes(connection);
                    return null;
                },
                SymbolSchema::validateForeignKeys);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + target.toAbsolutePath())) {
            assertEquals(1, pragmaUserVersion(connection));
        }
    }

    @Test
    void reportsAnActionableExclusiveLockTimeout() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.db");
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> {
                try (var lock = DatabaseLock.write(target, Duration.ofSeconds(1))) {
                    assertNotNull(lock);
                    acquired.countDown();
                    release.await();
                }
                return null;
            });
            assertTrue(acquired.await(5, TimeUnit.SECONDS));

            IOException exception = assertThrows(IOException.class, () -> new AtomicSqliteDatabase().rebuild(
                    target,
                    Duration.ofMillis(100),
                    _ -> "unreachable",
                    AtomicSqliteDatabaseTest::validateMarker));

            assertTrue(exception.getMessage().contains("exclusive database lock"));
            assertTrue(exception.getMessage().contains("retry"));
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        }
    }

    private static void createOldMarkerDatabase(Path database) throws SQLException, IOException {
        createMarkerDatabase(database, "old");
    }

    private static void createMarkerDatabase(Path database, String value) throws SQLException, IOException {
        Files.createDirectories(database.getParent());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            writeMarker(connection, value);
        }
    }

    private static void writeMarker(Connection connection, String value) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS marker");
            statement.execute("CREATE TABLE marker (value TEXT NOT NULL)");
        }
        //noinspection SqlNoDataSourceInspection,SqlResolve
        try (var statement = connection.prepareStatement("INSERT INTO marker(value) VALUES (?)")) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
    }

    private static void validateMarker(Connection connection) throws SQLException {
        assertEquals("new", marker(connection));
    }

    private static String marker(Path database) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            return marker(connection);
        }
    }

    private static String marker(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            //noinspection SqlNoDataSourceInspection,SqlResolve
            try (var results = statement.executeQuery("SELECT value FROM marker")) {
                assertTrue(results.next());
                return results.getString(1);
            }
        }
    }

    private static int pragmaUserVersion(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var results = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(results.next());
            return results.getInt(1);
        }
    }
}
