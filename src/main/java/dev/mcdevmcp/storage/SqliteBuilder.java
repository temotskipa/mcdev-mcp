package dev.mcdevmcp.storage;

import java.sql.Connection;

@FunctionalInterface
public interface SqliteBuilder<T> {
    T build(Connection connection) throws Exception;
}
