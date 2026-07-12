package dev.mcdevmcp.storage;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Subprocess entry point for real multi-process lock, query, and recovery tests.
 */
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
                        try (var results = statement.executeQuery("SELECT marker_value FROM marker")) {
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
                    new AtomicH2Database().rebuild(
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
