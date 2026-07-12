package dev.mcdevmcp.packaging;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

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
    void shadedJarDiscoversH2ServiceUnderNativeAccessDenial() throws Exception {
        Path testClasses = Path.of(H2ServiceLoaderProbeMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path database = Files.createTempDirectory("h2-service-loader-smoke").resolve("smoke");
        String classpath = testClasses + java.io.File.pathSeparator + JAR;
        var process = new ProcessBuilder(JAVA.toString(), "--illegal-native-access=deny", "-cp", classpath, H2ServiceLoaderProbeMain.class.getName(), database.toString(), JAR.toString()).start();
        String stdout;
        String stderr;
        try (var output = new BufferedReader(new InputStreamReader(process.getInputStream()));
             var errors = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            stdout = output.lines().collect(java.util.stream.Collectors.joining("\n"));
            stderr = errors.lines().collect(java.util.stream.Collectors.joining("\n"));
        }
        
        assertEquals(0, process.waitFor());
        assertEquals("H2_SERVICE_OK", stdout);
        assertEquals("", stderr);
    }
    
    @Test
    void shadedJarPrintsItsManifestVersion() throws Exception {
        var process = new ProcessBuilder(JAVA.toString(), "-jar", JAR.toString(), "--version").redirectErrorStream(true).start();
        String output;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.readLine();
        }
        
        assertEquals(0, process.waitFor());
        assertEquals(System.getProperty("mcdevMcpVersion"), output);
    }
    
    @Test
    void shadedJarStartsWithNativeAccessDeniedWithoutWarnings() throws Exception {
        var process = new ProcessBuilder(JAVA.toString(), "--illegal-native-access=deny", "-jar", JAR.toString(), "--version").start();
        String stdout;
        String stderr;
        try (var output = new BufferedReader(new InputStreamReader(process.getInputStream()));
             var errors = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            stdout = output.readLine();
            stderr = errors.lines().collect(java.util.stream.Collectors.joining("\n"));
        }
        
        assertEquals(0, process.waitFor());
        assertEquals(System.getProperty("mcdevMcpVersion"), stdout);
        assertEquals("", stderr);
    }
}
