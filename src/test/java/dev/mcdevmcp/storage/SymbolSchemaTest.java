package dev.mcdevmcp.storage;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

//noinspection SqlNoDataSourceInspection,SqlResolve
class SymbolSchemaTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsTypedH2MetadataAndNormalizedSchema() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.mv.db");
        Path sourceRoot = Path.of("/sources/client");
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.writer(database))) {
            SymbolSchema.create(connection, new MinecraftVersion("1.21.5"), sourceRoot, "a".repeat(64), Instant.parse("2026-07-12T12:00:00Z"));
            SymbolSchema.createIndexes(connection);
            SymbolSchema.validate(connection);

            assertEquals(
                    Set.of("METADATA", "PACKAGES", "TYPES", "TYPE_INTERFACES", "FIELDS", "METHODS", "PARAMETERS"),
                    tableNames(connection));
            try (var statement = connection.createStatement(); var results = statement.executeQuery(sql("SELECT schema_version, minecraft_version, source_root, remapped_jar_sha256, built_at FROM metadata"))) {
                assertTrue(results.next());
                assertEquals(1, results.getInt("schema_version"));
                assertEquals("1.21.5", results.getString("minecraft_version"));
                assertEquals(sourceRoot.toString(), results.getString("source_root"));
                assertEquals("a".repeat(64), results.getString("remapped_jar_sha256"));
                assertEquals(Instant.parse("2026-07-12T12:00:00Z"), results.getObject("built_at", java.time.OffsetDateTime.class).toInstant());
            }
        }
    }

    @Test
    void enforcesWireKindsNamespacesBooleansAndForeignKeys() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.mv.db");
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.writer(database))) {
            SymbolSchema.create(connection, new MinecraftVersion("1.21.5"), Path.of("/sources/client"), "b".repeat(64), Instant.now());
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(sql("INSERT INTO packages(source_namespace, fabric_api_version, name) VALUES ('minecraft', NULL, 'net.minecraft')"));
                statement.executeUpdate(sql("INSERT INTO types(package_id, source_namespace, fabric_api_version, binary_name, simple_name, kind, source_path, start_offset, end_offset, start_line, end_line) VALUES (1, 'minecraft', NULL, 'net.minecraft.Test', 'Test', 'class', 'Test.java', 0, 10, 1, 1)"));
                statement.executeUpdate(sql("INSERT INTO fields(type_id, ordinal, name, type, modifiers, start_offset, end_offset, start_line, end_line) VALUES (1, 0, 'field', 'int', 'public', 0, 1, 1, 1)"));
                statement.executeUpdate(sql("INSERT INTO methods(type_id, ordinal, name, descriptor, modifiers, constructor, start_offset, end_offset, start_line, end_line) VALUES (1, 0, 'method', '()V', 'public', TRUE, 0, 1, 1, 1)"));
            }
            SymbolSchema.validate(connection);
            assertFails(connection, "INSERT INTO packages(source_namespace, fabric_api_version, name) VALUES ('minecraft', '0.120.0', 'bad')");
            assertFails(connection, "INSERT INTO types(package_id, source_namespace, fabric_api_version, binary_name, simple_name, kind, source_path, start_offset, end_offset, start_line, end_line) VALUES (1, 'minecraft', NULL, 'net.minecraft.Bad', 'Bad', 'not-a-kind', 'Bad.java', 0, 1, 1, 1)");
            assertFails(connection, "INSERT INTO fields(type_id, ordinal, name, type, modifiers, start_offset, end_offset, start_line, end_line) VALUES (1, 0, 'again', 'int', 'public', 0, 1, 1, 1)");
            assertFails(connection, "INSERT INTO fields(type_id, ordinal, name, type, modifiers, start_offset, end_offset, start_line, end_line) VALUES (99, 1, 'orphan', 'int', 'public', 0, 1, 1, 1)");
        }
    }

    private static Set<String> tableNames(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var results = statement.executeQuery(sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'"))) {
            var names = new java.util.HashSet<String>();
            while (results.next()) {
                names.add(results.getString(1));
            }
            return Set.copyOf(names);
        }
    }

    private static void assertFails(Connection connection, String sql) {
        assertThrows(java.sql.SQLException.class, () -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        });
    }

    private static String sql(String statement) {
        return statement;
    }
}
