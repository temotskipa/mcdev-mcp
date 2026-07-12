package dev.mcdevmcp.storage;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record PlatformPaths(Path cacheRoot) {
    public PlatformPaths {
        cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot").normalize();
    }

    public static PlatformPaths forEnvironment(String osName, Map<String, String> env, Path home) {
        Objects.requireNonNull(osName, "osName");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(home, "home");
        String normalizedOsName = osName.toLowerCase(Locale.ROOT);
        if (normalizedOsName.contains("win")) {
            Path localApplicationData = Path.of(env.getOrDefault("LOCALAPPDATA", home.resolve("AppData").resolve("Local").toString()));
            return new PlatformPaths(localApplicationData.resolve("mcdev-mcp").resolve("Cache"));
        }
        if (normalizedOsName.contains("mac")) {
            return new PlatformPaths(home.resolve("Library").resolve("Caches").resolve("mcdev-mcp"));
        }
        Path xdgCache = Path.of(env.getOrDefault("XDG_CACHE_HOME", home.resolve(".cache").toString()));
        return new PlatformPaths(xdgCache.resolve("mcdev-mcp"));
    }

    public Path versionCache(String version) {
        return cacheRoot.resolve("cache").resolve(validVersion(version));
    }

    public Path sourceRoot(String version) {
        return versionCache(version).resolve("client");
    }

    public Path remappedJar(String version) {
        String safeVersion = validVersion(version);
        return versionCache(safeVersion).resolve("jars").resolve(safeVersion + "_unobfuscated.jar");
    }

    public Path remappedCallgraphJar(String version) {
        return versionCache(version).resolve("callgraph").resolve("client-remapped.jar");
    }

    public Path fabricSourceRoot(String version) {
        return cacheRoot.resolve("cache").resolve("fabric-api-" + validVersion(version));
    }

    public Path symbolDatabase(String version) {
        return indexRoot(version).resolve("symbols.db");
    }

    public Path callgraphDatabase(String version) {
        return versionCache(version).resolve("callgraph").resolve("callgraph.db");
    }

    public Path indexRoot(String version) {
        return cacheRoot.resolve("index").resolve(validVersion(version));
    }

    private static String validVersion(String version) {
        Objects.requireNonNull(version, "version");
        Path path = Path.of(version);
        if (version.isBlank() || path.isAbsolute() || path.getNameCount() != 1 || version.equals(".") || version.equals("..")) {
            throw new IllegalArgumentException("Invalid Minecraft version path component: " + version);
        }
        return version;
    }
}
