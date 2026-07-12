package dev.mcdevmcp.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

public final class SymbolRepository {
    private final Path database;
    
    public SymbolRepository(Path database) {
        this.database = Objects.requireNonNull(database, "database").toAbsolutePath().normalize();
    }
    
    private static String readOnlyUrl(Path database) {
        return "jdbc:sqlite:file:" + database.toAbsolutePath().toString().replace('\\', '/') + "?mode=ro";
    }
    
    public <T> T query(SqliteBuilder<T> query) throws IOException, SQLException {
        Objects.requireNonNull(query, "query");
        try (var databaseLock = DatabaseLock.read(database, AtomicSqliteDatabase.WRITE_LOCK_TIMEOUT);
             Connection connection = DriverManager.getConnection(readOnlyUrl(database))) {
            if (!databaseLock.isHeld()) {
                throw new IOException("Failed to acquire shared database lock");
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only = ON");
            }
            try {
                return query.build(connection);
            } catch (SQLException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new SQLException("Read-only symbol query failed", exception);
            }
        }
    }
}
