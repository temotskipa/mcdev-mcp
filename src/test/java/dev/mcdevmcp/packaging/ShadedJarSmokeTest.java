package dev.mcdevmcp.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class ShadedJarSmokeTest {
    private static final Path JAR = Path.of("build", "libs", "mcdev-mcp-3.0.0.jar");

    @Test
    void shadedJarHasRequiredManifestEntries() throws Exception {
        assertTrue(Files.isRegularFile(JAR));

        try (var jar = new JarFile(JAR.toFile())) {
            var manifest = jar.getManifest().getMainAttributes();
            assertEquals("dev.mcdevmcp.app.Main", manifest.getValue("Main-Class"));
            assertEquals("3.0.0", manifest.getValue("Implementation-Version"));
        }
    }

    @Test
    void shadedJarPrintsItsManifestVersion() throws Exception {
        var process = new ProcessBuilder("java", "-jar", JAR.toString(), "--version")
                .redirectErrorStream(true)
                .start();
        String output;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.readLine();
        }

        assertEquals(0, process.waitFor());
        assertEquals("3.0.0", output);
    }
}
