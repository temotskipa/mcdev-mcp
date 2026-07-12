package dev.mcdevmcp.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

//noinspection SqlNoDataSourceInspection,SqlResolve
class SymbolSchemaTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsSchemaVersionOneWithDurableSqliteSettingsAndMetadata() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.db");
        Path sourceRoot = Path.of("/sources/client");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            SymbolSchema.create(
                    connection,
                    "1.21.5",
                    sourceRoot,
                    "a".repeat(64),
                    Instant.parse("2026-07-12T12:00:00Z"));
            SymbolSchema.createIndexes(connection);

            assertEquals(1, pragmaInteger(connection, "user_version"));
            assertEquals("delete", journalMode(connection));
            assertEquals(2, pragmaInteger(connection, "synchronous"));
            assertEquals(1, pragmaInteger(connection, "foreign_keys"));
            assertEquals(
                    Set.of("metadata", "packages", "types", "type_interfaces", "fields", "methods", "parameters"),
                    tableNames(connection));
            assertEquals("1.21.5", metadata(connection, "minecraft_version"));
            assertEquals(sourceRoot.toString(), metadata(connection, "source_root"));
            assertEquals("a".repeat(64), metadata(connection, "remapped_jar_sha256"));
            assertEquals("2026-07-12T12:00:00Z", metadata(connection, "built_at"));
            assertTrue(indexNames(connection).stream().anyMatch(name -> name.contains("type_binary_name")));
        }
    }

    @Test
    void enforcesTypeKindsUniqueBinaryNamesMemberOrdinalsAndForeignKeys() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            SymbolSchema.create(connection, "1.21.5", Path.of("/sources/client"), "b".repeat(64), Instant.now());
            try (var statement = connection.createStatement()) {
                update(statement, "INSERT INTO packages(namespace, name) VALUES ('minecraft', 'net.minecraft')");
                update(statement, "INSERT INTO types(package_id, namespace, binary_name, simple_name, kind, source_path, start_offset, end_offset, start_line, end_line) VALUES (1, 'minecraft', 'net.minecraft.Test', 'Test', 'class', 'Test.java', 0, 10, 1, 1)");
                update(statement, "INSERT INTO fields(type_id, ordinal, name, type, modifiers, start_offset, end_offset, start_line, end_line) VALUES (1, 0, 'field', 'int', '', 0, 1, 1, 1)");
            }

            SymbolSchema.validateForeignKeys(connection);
            assertFails(connection, "INSERT INTO types(package_id, namespace, binary_name, simple_name, kind, source_path, start_offset, end_offset, start_line, end_line) VALUES (1, 'minecraft', 'net.minecraft.Test', 'Other', 'class', 'Other.java', 0, 1, 1, 1)");
            assertFails(connection, "INSERT INTO types(package_id, namespace, binary_name, simple_name, kind, source_path, start_offset, end_offset, start_line, end_line) VALUES (1, 'minecraft', 'net.minecraft.Bad', 'Bad', 'not-a-kind', 'Bad.java', 0, 1, 1, 1)");
            assertFails(connection, "INSERT INTO fields(type_id, ordinal, name, type, modifiers, start_offset, end_offset, start_line, end_line) VALUES (1, 0, 'again', 'int', '', 0, 1, 1, 1)");
            assertFails(connection, "INSERT INTO fields(type_id, ordinal, name, type, modifiers, start_offset, end_offset, start_line, end_line) VALUES (99, 1, 'orphan', 'int', '', 0, 1, 1, 1)");
        }
    }

    private static int pragmaInteger(Connection connection, String pragma) throws Exception {
        try (var statement = connection.createStatement(); var results = statement.executeQuery("PRAGMA " + pragma)) {
            assertTrue(results.next());
            return results.getInt(1);
        }
    }

    private static String journalMode(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var results = statement.executeQuery("PRAGMA journal_mode")) {
            assertTrue(results.next());
            return results.getString(1);
        }
    }

    private static Set<String> tableNames(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var results = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
            var names = new java.util.HashSet<String>();
            while (results.next()) {
                names.add(results.getString(1));
            }
            return Set.copyOf(names);
        }
    }

    private static Set<String> indexNames(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var results = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index'")) {
            var names = new java.util.HashSet<String>();
            while (results.next()) {
                names.add(results.getString(1));
            }
            return Set.copyOf(names);
        }
    }

    private static String metadata(Connection connection, String key) throws Exception {
        //noinspection SqlNoDataSourceInspection,SqlResolve
        try (var statement = connection.prepareStatement("SELECT value FROM metadata WHERE key = ?")) {
            statement.setString(1, key);
            try (var results = statement.executeQuery()) {
                assertTrue(results.next());
                return results.getString(1);
            }
        }
    }

    private static void assertFails(Connection connection, String sql) {
        try {
            try (var statement = connection.createStatement()) {
                update(statement, sql);
            }
        } catch (java.sql.SQLException expected) {
            return;
        }
        throw new AssertionError("Expected SQL to fail: " + sql);
    }

    private static void update(java.sql.Statement statement, String sql) throws java.sql.SQLException {
        statement.executeUpdate(sql);
    }

}
