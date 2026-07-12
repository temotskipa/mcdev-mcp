package dev.mcdevmcp.storage;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.VersionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlatformPathsTest {
    private static final MinecraftVersion VERSION = new MinecraftVersion("1.21.5");
    @TempDir
    Path temporaryDirectory;
    
    @Test
    void resolvesMacOsLinuxAndWindowsCacheRootsWithoutAPrivateEnvironmentOverride() {
        Path home = Path.of("/Users/alex");
        Path linuxHome = Path.of("/home/alex");
        
        assertEquals(Path.of("/Users/alex/Library/Caches/mcdev-mcp"), PlatformPaths.forEnvironment("Mac OS X", Map.of(), home).cacheRoot());
        assertEquals(Path.of("/home/alex/.cache/mcdev-mcp"), PlatformPaths.forEnvironment("Linux", Map.of(), linuxHome).cacheRoot());
        assertEquals(Path.of("/var/cache/alex/mcdev-mcp"), PlatformPaths.forEnvironment("Linux", Map.of("XDG_CACHE_HOME", "/var/cache/alex"), linuxHome).cacheRoot());
        assertEquals(Path.of("C:/Users/alex/AppData/Local/mcdev-mcp/Cache"), PlatformPaths.forEnvironment("Windows 11", Map.of("LOCALAPPDATA", "C:/Users/alex/AppData/Local"), Path.of("C:/Users/alex")).cacheRoot());
    }
    
    @Test
    void preservesTheVersionedCacheAndIndexLayout() {
        var paths = new PlatformPaths(Path.of("/cache/mcdev-mcp"));
        
        assertEquals(Path.of("/cache/mcdev-mcp/cache/1.21.5"), paths.versionCache(VERSION));
        assertEquals(Path.of("/cache/mcdev-mcp/cache/1.21.5/client"), paths.sourceRoot(VERSION));
        assertEquals(Path.of("/cache/mcdev-mcp/cache/1.21.5/jars/1.21.5_unobfuscated.jar"), paths.remappedJar(VERSION));
        assertEquals(Path.of("/cache/mcdev-mcp/cache/1.21.5/callgraph/client-remapped.jar"), paths.remappedCallgraphJar(VERSION));
        assertEquals(Path.of("/cache/mcdev-mcp/cache/1.21.5/callgraph/callgraph.mv.db"), paths.callgraphDatabase(VERSION));
        assertEquals(Path.of("/cache/mcdev-mcp/cache/fabric-api-1.21.5"), paths.fabricSourceRoot(VERSION));
        assertEquals(Path.of("/cache/mcdev-mcp/index/1.21.5/symbols.mv.db"), paths.symbolDatabase(VERSION));
    }
    
    @Test
    void reportsReadyLegacySourceOnlyAndAbsentVersions() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        var states = new VersionStateRepository(paths);
        
        assertEquals(VersionState.ABSENT, states.state(VERSION));
        assertTrue(states.isAbsent(VERSION));
        Files.createDirectories(paths.sourceRoot(VERSION));
        assertTrue(states.isSourceOnly(VERSION));
        Files.createDirectories(paths.symbolDatabase(VERSION).getParent());
        Files.writeString(paths.symbolDatabase(VERSION), "not-a-database");
        assertFalse(states.isH2Ready(VERSION));
        Files.delete(paths.symbolDatabase(VERSION));
        Files.writeString(paths.indexRoot(VERSION).resolve("manifest.json"), "{}");
        assertTrue(states.needsRebuild(VERSION));
    }
    
    @Test
    void cleansOnlyIndexFilesContainedByTheVersionRoot() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        Path indexRoot = paths.indexRoot(VERSION);
        Path outside = temporaryDirectory.resolve("outside.json");
        Files.createDirectories(indexRoot.resolve("minecraft/net/minecraft"));
        Files.writeString(paths.symbolDatabase(VERSION), "db");
        Files.writeString(paths.symbolDatabase(VERSION).resolveSibling("symbols.lock"), "lock");
        Files.writeString(paths.symbolDatabase(VERSION).resolveSibling("symbols.99.tmp.mv.db"), "tmp");
        Files.writeString(paths.symbolDatabase(VERSION).resolveSibling("symbols.mv.db.bak"), "backup");
        Files.writeString(indexRoot.resolve("manifest.json"), "{}");
        Files.writeString(indexRoot.resolve("minecraft/net/minecraft/world.json"), "{}");
        Files.writeString(outside, "keep");
        
        new IndexCleaner(paths).cleanIndex(VERSION);
        
        assertFalse(Files.exists(indexRoot));
        assertTrue(Files.exists(outside));
    }
}
