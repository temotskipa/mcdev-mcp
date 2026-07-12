package dev.mcdevmcp.storage;

import java.sql.Connection;

@FunctionalInterface
public interface DatabaseQuery<T> {
    T query(Connection connection) throws Exception;
}
