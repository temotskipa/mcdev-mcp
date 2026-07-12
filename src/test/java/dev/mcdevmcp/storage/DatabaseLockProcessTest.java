package dev.mcdevmcp.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

//noinspection SqlNoDataSourceInspection,SqlResolve
class DatabaseLockProcessTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sameJvmReadersOverlapAndReleaseTheSharedOperatingSystemLock() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.mv.db");
        var acquired = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> holdRead(database, acquired, release));
            var second = executor.submit(() -> holdRead(database, acquired, release));
            assertTrue(acquired.await(2, TimeUnit.SECONDS));
            assertThrows(java.io.IOException.class, () -> {
                try (var unexpected = DatabaseLock.write(database, Duration.ofMillis(100))) {
                    assertTrue(unexpected.isHeld(), "writer acquired while readers held the shared lock");
                    throw new AssertionError("writer acquired while readers held the shared lock");
                }
            });
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        }
        try (var writer = DatabaseLock.write(database, Duration.ofSeconds(1))) {
            assertTrue(writer.isHeld());
        }
    }

    @Test
    void twoReaderProcessesOverlapBeforeEitherBlocksAWritingProcess() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.mv.db");
        Process first = process("hold-read", database);
        Process second = process("hold-read", database);
        try (var firstOutput = new BufferedReader(new InputStreamReader(first.getInputStream(), StandardCharsets.UTF_8)); var secondOutput = new BufferedReader(new InputStreamReader(second.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("locked", firstOutput.readLine());
            assertEquals("locked", secondOutput.readLine());
            assertThrows(java.io.IOException.class, () -> {
                try (var unexpected = DatabaseLock.write(database, Duration.ofMillis(100))) {
                    assertTrue(unexpected.isHeld(), "writer acquired while reader processes held the shared lock");
                    throw new AssertionError("writer acquired while reader processes held the shared lock");
                }
            });
            first.getOutputStream().close();
            second.getOutputStream().close();
            assertTrue(first.waitFor(5, TimeUnit.SECONDS));
            assertTrue(second.waitFor(5, TimeUnit.SECONDS));
        } finally {
            stop(first);
            stop(second);
        }
    }

    @Test
    void aRealReaderProcessBlocksAnExclusiveWriterUntilItExits() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.mv.db");
        Process process = process("hold-read", database);
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("locked", reader.readLine());
            try (var lock = DatabaseLock.write(database, Duration.ofMillis(150))) {
                assertTrue(lock.isHeld(), "exclusive writer acquired while reader process held its shared lock");
                throw new AssertionError("exclusive writer acquired while reader process held its shared lock");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("exclusive database lock"));
            }

            process.getOutputStream().close();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            try (var lock = DatabaseLock.write(database, Duration.ofSeconds(1))) {
                assertTrue(lock.isHeld());
                assertTrue(Files.exists(database.resolveSibling("symbols.mv.db.lock")));
            }
        } finally {
            stop(process);
        }
    }

    @Test
    void aRealQueryProcessReleasesAllWindowsHandlesBeforeRename() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.mv.db");
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.writer(database));
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marker (marker_value VARCHAR)");
            //noinspection SqlNoDataSourceInspection,SqlResolve
            statement.execute("INSERT INTO marker(marker_value) VALUES ('value')");
        }
        Process process = process("query-and-close", database);
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("closed", reader.readLine());
            Path renamed = temporaryDirectory.resolve("renamed.mv.db");
            Files.move(database, renamed);
            assertTrue(Files.exists(renamed));
            process.getOutputStream().close();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        } finally {
            stop(process);
        }
    }

    @Test
    void aRealRecoveryProcessRestoresTheBackupWhenTheTargetIsMissing() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.mv.db");
        Path backup = database.resolveSibling("symbols.mv.db.bak");
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.writer(database));
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marker (marker_value VARCHAR)");
            //noinspection SqlNoDataSourceInspection,SqlResolve
            statement.execute("INSERT INTO marker(marker_value) VALUES ('old')");
        }
        Files.move(database, backup);

        Process process = process("recover-failing", database);
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("recovered", reader.readLine());
            process.getOutputStream().close();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertTrue(Files.exists(database));
            assertFalse(Files.exists(backup));
        } finally {
            stop(process);
        }
    }

    private static Process process(String mode, Path database) throws Exception {
        String java = System.getProperty("mcdevMcpJava");
        String classpath = System.getProperty("java.class.path");
        return new ProcessBuilder(
                        java,
                        "-cp",
                        classpath,
                        DatabaseLockProcessMain.class.getName(),
                        mode,
                        database.toString())
                .start();
    }

    private static Void holdRead(Path database, CountDownLatch acquired, CountDownLatch release) throws Exception {
        try (var lock = DatabaseLock.read(database, Duration.ofSeconds(1))) {
            assertTrue(lock.isHeld());
            acquired.countDown();
            if (!release.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("test did not release same-JVM readers");
            }
        }
        return null;
    }

    private static void stop(Process process) throws Exception {
        if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
