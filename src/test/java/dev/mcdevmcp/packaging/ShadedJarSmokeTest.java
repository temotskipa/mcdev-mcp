package dev.mcdevmcp.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.Properties;
import java.util.jar.JarFile;
import java.net.URLClassLoader;
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
            assertNull(manifest.getValue("Enable-Native-Access"));
            assertNotNull(jar.getEntry("META-INF/services/java.sql.Driver"));
            assertTrue(new String(jar.getInputStream(jar.getEntry("META-INF/services/java.sql.Driver")).readAllBytes()).contains("org.h2.Driver"));
            assertTrue(jar.stream().noneMatch(entry -> entry.getName().matches("META-INF/.*\\.(SF|RSA|DSA)")));
            assertTrue(jar.stream().noneMatch(entry -> entry.getName().startsWith("org/sqlite/") || entry.getName().startsWith("org/sqlite/native/")));
        }
    }

    @Test
    void shadedJarLoadsTheH2ServiceAndClosesAFileDatabase() throws Exception {
        Path database = Files.createTempDirectory("h2-jar-smoke").resolve("smoke");
        try (var classLoader = new URLClassLoader(new java.net.URL[]{JAR.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Driver driver = (Driver) Class.forName("org.h2.Driver", true, classLoader).getConstructor().newInstance();
            try (var connection = driver.connect("jdbc:h2:file:" + database + ";DB_CLOSE_ON_EXIT=FALSE;TRACE_LEVEL_FILE=0", new Properties());
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE smoke (id INT PRIMARY KEY)");
                statement.executeUpdate(sql("INSERT INTO smoke VALUES (1)"));
                try (var results = statement.executeQuery(sql("SELECT id FROM smoke"))) {
                    assertTrue(results.next());
                    assertEquals(1, results.getInt(1));
                }
            }
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

    @Test
    void shadedJarStartsWithNativeAccessDeniedWithoutWarnings() throws Exception {
        var process = new ProcessBuilder(JAVA.toString(), "--illegal-native-access=deny", "-jar", JAR.toString(), "--version")
                .start();
        String stdout;
        String stderr;
        try (var output = new BufferedReader(new InputStreamReader(process.getInputStream())); var errors = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            stdout = output.readLine();
            stderr = errors.lines().collect(java.util.stream.Collectors.joining("\n"));
        }

        assertEquals(0, process.waitFor());
        assertEquals(System.getProperty("mcdevMcpVersion"), stdout);
        assertEquals("", stderr);
    }

    private static String sql(String statement) {
        return statement;
    }
}
