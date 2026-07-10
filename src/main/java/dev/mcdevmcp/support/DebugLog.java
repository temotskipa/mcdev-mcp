package dev.mcdevmcp.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class DebugLog {
    private DebugLog() {}

    public static void write(AppEnvironment environment, String message) {
        environment.debugLogPath().ifPresent(path -> {
            try {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, message + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // Debug logging must never affect the protocol stream.
            }
        });
    }
}
