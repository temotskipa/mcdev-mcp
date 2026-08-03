package dev.mcdevmcp.bridge;

import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record SessionInfo(int port, MinecraftVersion version, BridgeMappingStatus mappingStatus, boolean obfuscated, long refs, Optional<Path> gameDir, Optional<Path> logsDir, Optional<Path> latestLog, Optional<Boolean> latestLogExists, Optional<Path> debugLog, Optional<Boolean> debugLogExists, Optional<Boolean> sessionControlEnabled) {
    public SessionInfo {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Bridge port must be in range: " + port);
        }
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(mappingStatus, "mappingStatus");
        if (refs < 0) {
            throw new IllegalArgumentException("Bridge reference count must not be negative: " + refs);
        }
        gameDir = Objects.requireNonNull(gameDir, "gameDir").map(path -> normalizePath("gameDir", path));
        logsDir = Objects.requireNonNull(logsDir, "logsDir").map(path -> normalizePath("logsDir", path));
        latestLog = Objects.requireNonNull(latestLog, "latestLog").map(path -> normalizePath("latestLog", path));
        Objects.requireNonNull(latestLogExists, "latestLogExists");
        debugLog = Objects.requireNonNull(debugLog, "debugLog").map(path -> normalizePath("debugLog", path));
        Objects.requireNonNull(debugLogExists, "debugLogExists");
        Objects.requireNonNull(sessionControlEnabled, "sessionControlEnabled");
    }

    private static Path normalizePath(String field, Path path) {
        if (!isAbsolutePath(path)) {
            throw new IllegalArgumentException("DebugBridge status " + field + " must be absolute: " + BridgePayloadValidator.safeDisplay(path));
        }
        return path.normalize();
    }

    // DebugBridge reports paths as seen by the Minecraft client, which may run on a
    // different operating system than this MCP server. A Windows drive path such as
    // `C:\Game` is not considered absolute by a POSIX Path, but is absolute on the
    // client; treat drive-letter-prefixed paths as absolute so a Windows client can
    // be used against a POSIX-hosted server.
    private static boolean isAbsolutePath(Path path) {
        if (path.isAbsolute()) {
            return true;
        }
        String text = path.toString();
        return text.length() >= 3 && Character.isLetter(text.charAt(0)) && text.charAt(1) == ':' && (text.charAt(2) == '\\' || text.charAt(2) == '/');
    }
}
