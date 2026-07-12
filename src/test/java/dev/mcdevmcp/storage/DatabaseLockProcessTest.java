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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

//noinspection SqlNoDataSourceInspection,SqlResolve
class DatabaseLockProcessTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void aRealReaderProcessBlocksAnExclusiveWriterUntilItExits() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.db");
        Process process = process("hold-read", database);
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("locked", reader.readLine());
            try (var lock = DatabaseLock.write(database, Duration.ofMillis(150))) {
                assertTrue(lock.isHeld());
                    throw new AssertionError("exclusive writer acquired while reader process held its shared lock");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("exclusive database lock"));
            }

            process.getOutputStream().close();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            try (var lock = DatabaseLock.write(database, Duration.ofSeconds(1))) {
                assertTrue(lock.isHeld());
                assertTrue(Files.exists(database.resolveSibling("symbols.db.lock")));
            }
        } finally {
            stop(process);
        }
    }

    @Test
    void aRealQueryProcessReleasesAllWindowsHandlesBeforeRename() throws Exception {
        Path database = temporaryDirectory.resolve("symbols.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath()); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marker (value TEXT)");
            //noinspection SqlNoDataSourceInspection,SqlResolve
            statement.execute("INSERT INTO marker VALUES ('value')");
        }
        Process process = process("query-and-close", database);
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("closed", reader.readLine());
            Path renamed = temporaryDirectory.resolve("renamed.db");
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
        Path database = temporaryDirectory.resolve("symbols.db");
        Path backup = database.resolveSibling("symbols.db.bak");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + backup.toAbsolutePath()); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marker (value TEXT)");
            //noinspection SqlNoDataSourceInspection,SqlResolve
            statement.execute("INSERT INTO marker VALUES ('old')");
        }

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
        return new ProcessBuilder(java, "-cp", classpath, DatabaseLockProcessMain.class.getName(), mode, database.toString())
                .start();
    }

    private static void stop(Process process) throws Exception {
        if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }
}

final class DatabaseLockProcessMain {
    private DatabaseLockProcessMain() {
    }

    static void main(String[] arguments) throws Exception {
        Path database = Path.of(arguments[1]);
        switch (arguments[0]) {
            case "hold-read" -> {
                try (var lock = DatabaseLock.read(database, Duration.ofSeconds(5))) {
                    if (!lock.isHeld()) {
                        throw new AssertionError("read lock was not acquired");
                    }
                    System.out.println("locked");
                    System.out.flush();
                    awaitParentExit();
                }
            }
            case "query-and-close" -> {
                var repository = new SymbolRepository(database);
                assertEquals("value", repository.query(connection -> {
                    try (var statement = connection.createStatement()) {
                        //noinspection SqlNoDataSourceInspection,SqlResolve
                        try (var results = statement.executeQuery("SELECT value FROM marker")) {
                            if (!results.next()) {
                                throw new java.sql.SQLException("marker missing");
                            }
                            return results.getString(1);
                        }
                    }
                }));
                System.out.println("closed");
                System.out.flush();
                awaitParentExit();
            }
            case "recover-failing" -> {
                try {
                    new AtomicSqliteDatabase().rebuild(
                            database,
                            Duration.ofSeconds(1),
                            _ -> {
                                throw new java.sql.SQLException("intentional rebuild failure");
                            },
                            _ -> {
                            });
                    throw new AssertionError("rebuild unexpectedly succeeded");
                } catch (java.sql.SQLException expected) {
                    System.out.println("recovered");
                    System.out.flush();
                }
            }
            default -> throw new IllegalArgumentException("Unsupported process mode: " + arguments[0]);
        }
    }

    private static void awaitParentExit() throws java.io.IOException {
        int input;
        do {
            input = System.in.read();
        } while (input != -1);
    }
}
