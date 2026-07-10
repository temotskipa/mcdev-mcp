package dev.mcdevmcp.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class MainTest {
    @Test
    void versionExitsSuccessfullyAndPrintsTheReleaseVersion() {
        var output = new StringWriter();

        int exitCode = Main.execute(new String[] {"--version"}, 25, new PrintWriter(output), new PrintWriter(new StringWriter()));

        assertEquals(0, exitCode);
        assertEquals(System.getProperty("mcdevMcpVersion") + System.lineSeparator(), output.toString());
    }

    @Test
    void rejectsJavaBelow25BeforeCommandExecution() {
        var error = new StringWriter();

        int exitCode = Main.execute(new String[] {"--version"}, 24, new PrintWriter(new StringWriter()), new PrintWriter(error));

        assertEquals(1, exitCode);
        assertTrue(error.toString().contains("Java 25 or newer is required"));
    }
}
