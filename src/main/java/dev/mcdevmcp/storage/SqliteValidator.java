package dev.mcdevmcp.storage;

import java.sql.Connection;

@FunctionalInterface
public interface SqliteValidator {
    void validate(Connection connection) throws Exception;
}
