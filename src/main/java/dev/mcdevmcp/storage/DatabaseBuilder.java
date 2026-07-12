package dev.mcdevmcp.storage;

import java.sql.Connection;

@FunctionalInterface
public interface DatabaseBuilder<T> {
    T build(Connection connection) throws Exception;
}
