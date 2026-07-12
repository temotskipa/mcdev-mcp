package dev.mcdevmcp.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformPathsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesMacOsLinuxAndWindowsCacheRootsWithoutAPrivateEnvironmentOverride() {
        Path home = Path.of("/Users/alex");
        Path linuxHome = Path.of("/home/alex");

        assertEquals(
                Path.of("/Users/alex/Library/Caches/mcdev-mcp"),
                PlatformPaths.forEnvironment("Mac OS X", Map.of(), home).cacheRoot());
        assertEquals(
                Path.of("/home/alex/.cache/mcdev-mcp"),
                PlatformPaths.forEnvironment("Linux", Map.of(), linuxHome).cacheRoot());
        assertEquals(
                Path.of("/var/cache/alex/mcdev-mcp"),
                PlatformPaths.forEnvironment("Linux", Map.of("XDG_CACHE_HOME", "/var/cache/alex"), linuxHome).cacheRoot());
        assertEquals(
                Path.of("C:/Users/alex/AppData/Local/mcdev-mcp/Cache"),
                PlatformPaths.forEnvironment("Windows 11", Map.of("LOCALAPPDATA", "C:/Users/alex/AppData/Local"), Path.of("C:/Users/alex")).cacheRoot());
    }

    @Test
    void preservesTheVersionedCacheAndIndexLayout() {
        var paths = new PlatformPaths(Path.of("/cache/mcdev-mcp"));

        assertEquals(Path.of("/cache/mcdev-mcp/cache/1.21.5"), paths.versionCache("1.21.5"));
        assertEquals(Path.of("/cache/mcdev-mcp/cache/1.21.5/client"), paths.sourceRoot("1.21.5"));
        assertEquals(Path.of("/cache/mcdev-mcp/cache/1.21.5/jars/1.21.5_unobfuscated.jar"), paths.remappedJar("1.21.5"));
        assertEquals(Path.of("/cache/mcdev-mcp/cache/1.21.5/callgraph/client-remapped.jar"), paths.remappedCallgraphJar("1.21.5"));
        assertEquals(Path.of("/cache/mcdev-mcp/cache/1.21.5/callgraph/callgraph.db"), paths.callgraphDatabase("1.21.5"));
        assertEquals(Path.of("/cache/mcdev-mcp/cache/fabric-api-0.120.0"), paths.fabricSourceRoot("0.120.0"));
        assertEquals(Path.of("/cache/mcdev-mcp/index/1.21.5/symbols.db"), paths.symbolDatabase("1.21.5"));
    }

    @Test
    void reportsReadyLegacySourceOnlyAndAbsentVersions() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        var states = new VersionStateRepository(paths);

        assertTrue(states.isAbsent("1.21.5"));
        Files.createDirectories(paths.sourceRoot("1.21.5"));
        assertTrue(states.isSourceOnly("1.21.5"));
        Files.createDirectories(paths.symbolDatabase("1.21.5").getParent());
        Files.writeString(paths.symbolDatabase("1.21.5"), "not-a-database");
        assertFalse(states.isSqliteReady("1.21.5"));
        Files.delete(paths.symbolDatabase("1.21.5"));
        Files.writeString(paths.indexRoot("1.21.5").resolve("manifest.json"), "{}");
        assertTrue(states.needsRebuild("1.21.5"));
    }

    @Test
    void cleansOnlyIndexFilesContainedByTheVersionRoot() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        Path indexRoot = paths.indexRoot("1.21.5");
        Path outside = temporaryDirectory.resolve("outside.json");
        Files.createDirectories(indexRoot.resolve("minecraft/net/minecraft"));
        Files.writeString(paths.symbolDatabase("1.21.5"), "db");
        Files.writeString(paths.symbolDatabase("1.21.5").resolveSibling("symbols.db.lock"), "lock");
        Files.writeString(paths.symbolDatabase("1.21.5").resolveSibling("symbols.db.99.tmp"), "tmp");
        Files.writeString(paths.symbolDatabase("1.21.5").resolveSibling("symbols.db.bak"), "backup");
        Files.writeString(indexRoot.resolve("manifest.json"), "{}");
        Files.writeString(indexRoot.resolve("minecraft/net/minecraft/world.json"), "{}");
        Files.writeString(outside, "keep");

        new IndexCleaner(paths).cleanIndex("1.21.5");

        assertFalse(Files.exists(indexRoot));
        assertTrue(Files.exists(outside));
    }
}
