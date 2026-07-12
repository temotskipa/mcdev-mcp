package dev.mcdevmcp.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

public final class SymbolRepository {
    private final Path database;
    
    public SymbolRepository(Path database) {
        this.database = Objects.requireNonNull(database, "database").toAbsolutePath().normalize();
    }
    
    public <T> T query(DatabaseQuery<T> query) throws IOException, SQLException {
        Objects.requireNonNull(query, "query");
        try (var databaseLock = DatabaseLock.read(database, AtomicH2Database.WRITE_LOCK_TIMEOUT);
             Connection connection = DriverManager.getConnection(H2DatabaseUrls.reader(database))) {
            if (!databaseLock.isHeld()) {
                throw new IOException("Failed to acquire shared database lock");
            }
            try {
                return query.query(connection);
            } catch (SQLException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new SQLException("Read-only symbol query failed", exception);
            }
        }
    }
}
