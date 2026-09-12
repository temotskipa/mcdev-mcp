package dev.mcdevmcp.tools.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionControlProcessIntegrationTest {
    @Test
    void drainsLargeStdoutAndStderrFromARealChild() throws Exception {
        AtomicReference<Process> captured = new AtomicReference<>();
        SessionControlSupport.ListeningPidResolver resolver = realChild(captured, "success");

        CompletableFuture<Long> result = resolver.resolve(9876).toCompletableFuture();

        assertEquals(4242L, result.get(5, TimeUnit.SECONDS));
        Process process = captured.get();
        assertNotNullProcess(process);
        assertFalse(process.isAlive());
    }

    @Test
    void destroysARealHangingChildAtTheFourSecondDeadline() throws Exception {
        AtomicReference<Process> captured = new AtomicReference<>();
        SessionControlSupport.ListeningPidResolver resolver = realChild(captured, "hang");
        long started = System.nanoTime();

        assertNull(resolver.resolve(9876).toCompletableFuture().get(6, TimeUnit.SECONDS));
        assertTrue(System.nanoTime() - started >= Duration.ofSeconds(3).toNanos());
        Process process = captured.get();
        assertNotNullProcess(process);
        assertTrue(process.waitFor(1, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
    }

    @Test
    void cancellationDestroysARealRunningChildAndWaitsForOwnerCleanup() throws Exception {
        AtomicReference<Process> captured = new AtomicReference<>();
        SessionControlSupport.ListeningPidResolver resolver = realChild(captured, "hang");
        CompletableFuture<Long> result = resolver.resolve(9876).toCompletableFuture();
        long childDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (captured.get() == null && System.nanoTime() < childDeadline) {
            Thread.sleep(10);
        }
        Process process = captured.get();
        assertNotNullProcess(process);
        assertTrue(result.cancel(true));
        assertTrue(result.isCancelled());
        assertTrue(process.waitFor(1, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
        long ownerDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!SessionControlSupport.ownerStopped(result) && System.nanoTime() < ownerDeadline) {
            Thread.sleep(10);
        }
        assertTrue(SessionControlSupport.ownerStopped(result));
    }

    private static SessionControlSupport.ListeningPidResolver realChild(AtomicReference<Process> captured, String mode) {
        return SessionControlSupport.listeningPidResolver(command -> {
            List<String> childCommand = List.of(javaExecutable(), "-cp", System.getProperty("java.class.path"), SessionControlProcessFixture.class.getName(), mode);
            Process process = new ProcessBuilder(childCommand).start();
            captured.set(process);
            return process;
        });
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static void assertNotNullProcess(Process process) {
        assertTrue(process != null, "child process was never started");
    }
}