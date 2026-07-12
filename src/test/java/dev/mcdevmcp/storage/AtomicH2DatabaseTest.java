package dev.mcdevmcp.storage;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AtomicH2DatabaseTest {
    @TempDir
    Path temporaryDirectory;
    
    private static void createDatabase(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.writer(database))) {
            createMarker(connection, "old");
        }
    }
    
    private static void createMarker(Connection connection, String value) throws SQLException {
        SymbolSchema.create(connection, new MinecraftVersion("1.21.5"), Path.of("client"), "a".repeat(64), java.time.Instant.EPOCH);
        SymbolSchema.createIndexes(connection);
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
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.reader(database));
             var statement = connection.createStatement(); var results = statement.executeQuery(markerSelectSql())) {
            assertTrue(results.next());
            return results.getString(1);
        }
    }
    
    private static String markerSelectSql() {
        return "SELECT marker_value FROM marker";
    }
    
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
        assertThrows(IllegalArgumentException.class, () -> new AtomicH2Database().rebuild(temporaryDirectory.resolve("bad;name.mv.db"), Duration.ofSeconds(1), _ -> null, _ -> {
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
        
        var moves = new ForcedFallbackMoveStrategy();
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
        
        new AtomicH2Database(new ForcedFallbackMoveStrategy()).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, AtomicH2DatabaseTest::validateMarker);
        
        assertEquals("new", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.mv.db.bak")));
    }
    
    @Test
    void forcedBackupFallbackRemovesInvalidTargetWithoutAnOldDatabase() {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        
        assertThrows(SQLException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy()).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, _ -> {
            throw new SQLException("reject promoted database");
        }));
        
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.mv.db.bak")));
    }
    
    @Test
    void preservesOldTargetWhenTheFirstFallbackMoveFails() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        createDatabase(target);
        byte[] original = Files.readAllBytes(target);
        
        IOException failure = assertThrows(IOException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy(1)).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, AtomicH2DatabaseTest::validateMarker));
        
        assertEquals("forced fallback move failure 1", failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals("old", marker(target));
        assertFalse(Files.exists(target.resolveSibling("symbols.mv.db.bak")));
    }
    
    @Test
    void rejectsNumberedTemporaryCompanionWithoutTouchingUnrelatedSiblings() throws Exception {
        Path target = temporaryDirectory.resolve("symbols.mv.db");
        createDatabase(target);
        Path temporaryBase = temporaryDirectory.resolve("symbols." + ProcessHandle.current().pid() + ".tmp");
        Path numberedCompanion = temporaryBase.resolveSibling(temporaryBase.getFileName() + ".7.temp.db");
        Path unrelated = temporaryDirectory.resolve("unrelated.7.temp.db");
        Files.writeString(unrelated, "keep");
        
        IOException failure = assertThrows(IOException.class, () -> new AtomicH2Database(new ForcedFallbackMoveStrategy(numberedCompanion)).rebuild(target, Duration.ofSeconds(1), connection -> {
            createMarker(connection, "new");
            return null;
        }, AtomicH2DatabaseTest::validateMarker));
        
        assertTrue(failure.getMessage().contains(numberedCompanion.toString()));
        assertEquals("old", marker(target));
        assertFalse(Files.exists(numberedCompanion));
        assertEquals("keep", Files.readString(unrelated));
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
}
