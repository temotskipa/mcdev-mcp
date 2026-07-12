package dev.mcdevmcp.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;

import dev.mcdevmcp.storage.model.MinecraftVersion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicH2DatabaseTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsOneClosedMvStoreFileAndRejectsUnsafePaths() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");

        String result = new AtomicH2Database().rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return "built";
        }, AtomicH2DatabaseTest::validateMarker);

        assertEquals("built", result);
        assertEquals("new", marker(target));
        assertTrue(Files.isRegularFile(target));
        assertFalse(Files.exists(temporaryDirectory.resolve("symbols.lock.db")));
        assertFalse(Files.exists(temporaryDirectory.resolve("symbols.trace.db")));
        assertThrows(IllegalArgumentException.class, () -> new AtomicH2Database().rebuild(
                temporaryDirectory.resolve("bad;name.mv.db"),
                Duration.ofSeconds(1),
                _ -> null,
                _ -> {
                }));
    }

    @Test
    void acceptsH2PathsWithSpacesHashUnicodeAndWindowsSeparators() throws Exception {
        Path directory = temporaryDirectory.resolve("space # unicode-é");
        Path target = directory.resolve("symbols.mv.db");

        new AtomicH2Database().rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "special");
            return null;
        }, AtomicH2DatabaseTest::validateMarker);

        assertEquals("special", marker(target));
        assertTrue(H2DatabaseUrls.writer(target).contains(target.toAbsolutePath().getParent().toString()));
    }

    @Test
    void leavesOldDatabaseUnchangedWhenBuilderOrValidatorFails() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        createDatabase(target);
        byte[] oldBytes = Files.readAllBytes(target);

        assertThrows(SQLException.class, () -> new AtomicH2Database().rebuild(target, Duration.ofSeconds(1), _ -> {
            throw new SQLException("builder failed");
        }, AtomicH2DatabaseTest::validateMarker));
        assertEquals("old", marker(target));
        assertEquals(java.util.Arrays.toString(oldBytes), java.util.Arrays.toString(Files.readAllBytes(target)));

        assertThrows(SQLException.class, () -> new AtomicH2Database().rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, _ -> {
            throw new SQLException("validator failed");
        }));
        assertEquals("old", marker(target));
    }

    @Test
    void forcedBackupFallbackRestoresOldTargetWhenPostPromotionValidationFails() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        createDatabase(target);

        var moves = new FirstMoveIsNotAtomic();
        var database = new AtomicH2Database(moves);
        assertThrows(SQLException.class, () -> database.rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, _ -> {
            throw new SQLException("reject promoted database");
        }));

        assertEquals("old", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.mv.db.bak")));
    }

    @Test
    void forcedBackupFallbackPromotesAndDeletesTheBackup() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        createDatabase(target);

        new AtomicH2Database(new FirstMoveIsNotAtomic()).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, AtomicH2DatabaseTest::validateMarker);

        assertEquals("new", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.mv.db.bak")));
    }

    @Test
    void forcedBackupFallbackRemovesInvalidTargetWithoutAnOldDatabase() {
        Path target = temporaryDirectory.resolve("symbols.mv.db");

        assertThrows(SQLException.class, () -> new AtomicH2Database(new FirstMoveIsNotAtomic()).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, _ -> {
            throw new SQLException("reject promoted database");
        }));

        assertFalse(Files.exists(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.mv.db.bak")));
    }

    @Test
    void restoresBackupWhenStartupFindsNoTarget() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        Path backup = target.resolveSibling("symbols.mv.db.bak");
        createDatabase(target);
        Files.move(target, backup);

        assertThrows(SQLException.class, () -> new AtomicH2Database().rebuild(target, Duration.ofSeconds(1), _ -> {
            throw new SQLException("stop after recovery");
        }, AtomicH2DatabaseTest::validateMarker));

        assertEquals("old", marker(target));
        assertFalse(Files.exists(backup));
    }

    private static void createDatabase(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.writer(database))) {
            createMarker(connection, "old");
        }
    }

    private static void createMarker(Connection connection, String value) throws SQLException {
        SymbolSchema.create(connection, new MinecraftVersion("1.21.5"), Path.of("client"), "a".repeat(64), java.time.Instant.EPOCH);
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marker (marker_value VARCHAR)");
            statement.executeUpdate("INSERT INTO marker(marker_value) VALUES ('" + value + "')");
        }
    }

    private static void validateMarker(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var results = statement.executeQuery(markerSelectSql())) {
            if (!results.next()) {
                throw new SQLException("marker missing");
            }
        }
    }

    private static String marker(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.reader(database)); var statement = connection.createStatement(); var results = statement.executeQuery(markerSelectSql())) {
            assertTrue(results.next());
            return results.getString(1);
        }
    }

    private static final class FirstMoveIsNotAtomic implements DatabaseMoveStrategy {
        private boolean first = true;

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            if (first) {
                first = false;
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "forced by test");
            }
            Files.move(source, target, options);
        }
    }

    private static String markerSelectSql() {
        return "SELECT marker_value FROM marker";
    }
}
