package dev.mcdevmcp.tools.runtime;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.util.ArrayList;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptLoggerTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    @Test
    void prunesRotatedFilesOlderThanRetentionAndKeepsRecentOnes(@TempDir Path temporary) throws Exception {
        long now = 2_000_000_000_000L;
        var diagnostics = new java.util.ArrayList<String>();
        var logger = new ScriptLogger(temporary, MAPPER, diagnostics::add, () -> false, () -> now);

        Path logs = logger.logDirectory();
        Files.createDirectories(logs);

        // A rotated file far older than the 3-day retention window.
        Path ancient = logs.resolve("all.1000.jsonl");
        Files.writeString(ancient, "ancient");

        // A rotated file inside the retention window.
        Path recent = logs.resolve("all." + (now - 60_000L) + ".jsonl");
        Files.writeString(recent, "recent");

        // Make the live file exceed the rotation cap so that rotation triggers cleanup.
        Files.write(logger.allLogPath(), new byte[(int) ScriptLogger.MAX_LOG_BYTES + 1]);
        logger.rotateIfNeeded();

        assertFalse(Files.exists(ancient), "3-day-old rotated file should be deleted");
        assertTrue(Files.exists(recent), "recent rotated file should be kept");
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void movesLiveFileToTimestampedRotationOnRotation(@TempDir Path temporary) throws Exception {
        long now = 2_000_000_000_000L;
        var logger = new ScriptLogger(temporary, MAPPER, new ArrayList<String>()::add, () -> true, () -> now);

        Files.createDirectories(logger.logDirectory());
        Files.write(logger.allLogPath(), new byte[(int) ScriptLogger.MAX_LOG_BYTES + 1]);
        logger.rotateIfNeeded();

        assertFalse(Files.exists(logger.allLogPath()), "oversized live file should be rotated away");
        assertTrue(Files.exists(logger.logDirectory().resolve("all." + now + ".jsonl")));
    }
}
