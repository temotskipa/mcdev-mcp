package dev.mcdevmcp.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class ShadedJarSmokeTest {
    private static final Path JAR = Path.of(System.getProperty("mcdevMcpJar"));
    private static final Path JAVA = Path.of(System.getProperty("mcdevMcpJava"));

    @Test
    void shadedJarHasRequiredManifestEntries() throws Exception {
        assertTrue(Files.isRegularFile(JAR));

        try (var jar = new JarFile(JAR.toFile())) {
            var manifest = jar.getManifest().getMainAttributes();
            assertEquals("dev.mcdevmcp.app.Main", manifest.getValue("Main-Class"));
            assertEquals(System.getProperty("mcdevMcpVersion"), manifest.getValue("Implementation-Version"));
            assertNotNull(jar.getEntry("META-INF/services/java.sql.Driver"));
            assertTrue(new String(jar.getInputStream(jar.getEntry("META-INF/services/java.sql.Driver")).readAllBytes()).contains("org.sqlite.JDBC"));
            assertTrue(jar.stream().noneMatch(entry -> entry.getName().matches("META-INF/.*\\.(SF|RSA|DSA)")));
            assertTrue(jar.stream().anyMatch(entry -> entry.getName().startsWith("org/sqlite/native/") && entry.getName().endsWith(".dll")));
            assertTrue(jar.stream().anyMatch(entry -> entry.getName().startsWith("org/sqlite/native/") && entry.getName().endsWith(".so")));
            assertTrue(jar.stream().anyMatch(entry -> entry.getName().startsWith("org/sqlite/native/") && entry.getName().endsWith(".dylib")));
        }
    }

    @Test
    void shadedJarPrintsItsManifestVersion() throws Exception {
        var process = new ProcessBuilder(JAVA.toString(), "-jar", JAR.toString(), "--version")
                .redirectErrorStream(true)
                .start();
        String output;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.readLine();
        }

        assertEquals(0, process.waitFor());
        assertEquals(System.getProperty("mcdevMcpVersion"), output);
    }
}
