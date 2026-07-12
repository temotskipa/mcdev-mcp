package dev.mcdevmcp.storage;

import java.sql.Connection;

@FunctionalInterface
public interface DatabaseValidator {
    void validate(Connection connection) throws Exception;
}
