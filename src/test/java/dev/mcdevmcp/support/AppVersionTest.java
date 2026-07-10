package dev.mcdevmcp.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppVersionTest {
    @Test
    void readsTheGradleFilteredVersionFromClasses() {
        assertEquals("3.0.0", AppVersion.current());
    }

    @Test
    void debugLogEnvironmentRulesMatchTheNodeServer() {
        assertFalse(new AppEnvironment(Map.of()).debugLogPath().isPresent());
        assertFalse(new AppEnvironment(Map.of("MCDEV_MCP_DEBUG_LOG", "")).debugLogPath().isPresent());
        assertFalse(new AppEnvironment(Map.of("MCDEV_MCP_DEBUG_LOG", "off")).debugLogPath().isPresent());
        assertEquals(Path.of("/tmp/mcdev-debug.log"), new AppEnvironment(Map.of("MCDEV_MCP_DEBUG_LOG", "on")).debugLogPath().orElseThrow());
        assertEquals(Path.of("logs/mcdev.log"), new AppEnvironment(Map.of("MCDEV_MCP_DEBUG_LOG", "logs/mcdev.log")).debugLogPath().orElseThrow());
    }

    @Test
    void truthyValuesAndIndexThreadLimitsAreDeterministic() {
        var environment = new AppEnvironment(Map.of("FLAG", "true", "MCDEV_INDEX_THREADS", "99"));

        assertTrue(environment.isTruthy("FLAG"));
        assertFalse(new AppEnvironment(Map.of("FLAG", "yes")).isTruthy("FLAG"));
        assertFalse(new AppEnvironment(Map.of("FLAG", "on")).isTruthy("FLAG"));
        assertEquals(8, environment.indexThreads(8));
    }

    @Test
    void debugLogWritesOnlyToItsConfiguredFileAndSwallowsFailures() throws Exception {
        Path directory = Files.createTempDirectory("mcdev-debug-log");
        Path logPath = directory.resolve("debug.log");

        DebugLog.write(new AppEnvironment(Map.of("MCDEV_MCP_DEBUG_LOG", logPath.toString())), "diagnostic");
        DebugLog.write(new AppEnvironment(Map.of("MCDEV_MCP_DEBUG_LOG", directory.toString())), "ignored");

        assertEquals("diagnostic" + System.lineSeparator(), Files.readString(logPath));
    }
}
