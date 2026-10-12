package dev.mcdevmcp.storage.h2;

import java.nio.file.Path;

@FunctionalInterface
public interface DatabaseValidator {
    void validate(Path file) throws Exception;
}
