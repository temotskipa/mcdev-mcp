package dev.mcdevmcp.storage.h2;

import java.nio.file.Path;

@FunctionalInterface
public interface DatabaseBuilder<T> {
    T build(Path temporary) throws Exception;
}
