package dev.mcdevmcp.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;

//noinspection SqlNoDataSourceInspection,SqlResolve
public final class SymbolSchema {
    public static final int VERSION = 1;

    private SymbolSchema() {
    }

    public static void create(Connection connection, String minecraftVersion, Path sourceRoot, String remappedJarSha256, Instant builtAt) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(remappedJarSha256, "remappedJarSha256");
        Objects.requireNonNull(builtAt, "builtAt");
        try (Statement statement = connection.createStatement()) {
            if (connection.getAutoCommit()) {
            execute(statement, "PRAGMA journal_mode = DELETE");
            execute(statement, "PRAGMA synchronous = FULL");
            execute(statement, "PRAGMA foreign_keys = ON");
            }
            execute(statement, "PRAGMA user_version = " + VERSION);
            execute(statement, "CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            execute(statement, "CREATE TABLE packages (id INTEGER PRIMARY KEY, namespace TEXT NOT NULL CHECK(namespace IN ('minecraft', 'fabric')), name TEXT NOT NULL, UNIQUE(namespace, name))");
            execute(statement, "CREATE TABLE types (id INTEGER PRIMARY KEY, package_id INTEGER NOT NULL REFERENCES packages(id), namespace TEXT NOT NULL CHECK(namespace IN ('minecraft', 'fabric')), binary_name TEXT NOT NULL UNIQUE, simple_name TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('class', 'interface', 'enum', 'record', 'annotation')), superclass_binary_name TEXT, source_path TEXT NOT NULL, start_offset INTEGER NOT NULL, end_offset INTEGER NOT NULL, start_line INTEGER NOT NULL, end_line INTEGER NOT NULL)");
            execute(statement, "CREATE TABLE type_interfaces (type_id INTEGER NOT NULL REFERENCES types(id) ON DELETE CASCADE, ordinal INTEGER NOT NULL, interface_binary_name TEXT NOT NULL, PRIMARY KEY(type_id, ordinal))");
            execute(statement, "CREATE TABLE fields (id INTEGER PRIMARY KEY, type_id INTEGER NOT NULL REFERENCES types(id) ON DELETE CASCADE, ordinal INTEGER NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, modifiers TEXT NOT NULL, start_offset INTEGER NOT NULL, end_offset INTEGER NOT NULL, start_line INTEGER NOT NULL, end_line INTEGER NOT NULL, UNIQUE(type_id, ordinal))");
            execute(statement, "CREATE TABLE methods (id INTEGER PRIMARY KEY, type_id INTEGER NOT NULL REFERENCES types(id) ON DELETE CASCADE, ordinal INTEGER NOT NULL, name TEXT NOT NULL, descriptor TEXT NOT NULL, return_type TEXT, modifiers TEXT NOT NULL, constructor INTEGER NOT NULL CHECK(constructor IN (0, 1)), start_offset INTEGER NOT NULL, end_offset INTEGER NOT NULL, start_line INTEGER NOT NULL, end_line INTEGER NOT NULL, UNIQUE(type_id, ordinal))");
            execute(statement, "CREATE TABLE parameters (id INTEGER PRIMARY KEY, method_id INTEGER NOT NULL REFERENCES methods(id) ON DELETE CASCADE, ordinal INTEGER NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, varargs INTEGER NOT NULL CHECK(varargs IN (0, 1)), start_offset INTEGER NOT NULL, end_offset INTEGER NOT NULL, start_line INTEGER NOT NULL, end_line INTEGER NOT NULL, UNIQUE(method_id, ordinal))");
        }
        //noinspection SqlNoDataSourceInspection,SqlResolve
        try (var statement = connection.prepareStatement("INSERT INTO metadata(key, value) VALUES (?, ?)")) {
            insertMetadata(statement, "minecraft_version", minecraftVersion);
            insertMetadata(statement, "source_root", sourceRoot.toString());
            insertMetadata(statement, "remapped_jar_sha256", remappedJarSha256);
            insertMetadata(statement, "built_at", builtAt.toString());
        }
    }

    public static void createIndexes(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            execute(statement, "CREATE INDEX idx_type_binary_name ON types(binary_name)");
            execute(statement, "CREATE INDEX idx_types_package_name ON types(package_id, simple_name)");
            execute(statement, "CREATE INDEX idx_fields_type_name ON fields(type_id, name, ordinal)");
            execute(statement, "CREATE INDEX idx_methods_type_name ON methods(type_id, name, ordinal)");
            execute(statement, "CREATE INDEX idx_type_interfaces_binary_name ON type_interfaces(interface_binary_name, type_id)");
        }
    }

    public static void validateForeignKeys(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet results = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (results.next()) {
                throw new SQLException("SQLite foreign key check failed for table " + results.getString(1));
            }
        }
    }

    private static void insertMetadata(java.sql.PreparedStatement statement, String key, String value) throws SQLException {
        statement.setString(1, key);
        statement.setString(2, value);
        statement.executeUpdate();
    }

    private static void execute(Statement statement, String sql) throws SQLException {
        statement.execute(sql);
    }

}
