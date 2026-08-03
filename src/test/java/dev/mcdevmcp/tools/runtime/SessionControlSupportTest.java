package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeMappingStatus;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.bridge.BridgeTestHarness;
import dev.mcdevmcp.bridge.SessionInfo;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SessionControlSupportTest {
    private static final Path GAME_DIRECTORY = Path.of("C:\\Game");

    @Test
    void classifiesJoinedDisconnectedPendingAndStaleWorldTransitions() {
        assertInstanceOf(InWorldPollResult.Joined.class, SessionControlSupport.classifyInWorldPoll(Map.of("player", Map.of()), Map.of("type", "ChatScreen")));
        InWorldPollResult.Failed failed = assertInstanceOf(InWorldPollResult.Failed.class, SessionControlSupport.classifyInWorldPoll(Map.of(), Map.of("type", "net.minecraft.DisconnectedScreen", "title", "Connection refused")));
        assertEquals("Connection refused", failed.reason());
        assertEquals("DisconnectedScreen", assertInstanceOf(InWorldPollResult.Failed.class, SessionControlSupport.classifyInWorldPoll(null, Map.of("type", "DisconnectedScreen", "title", ""))).reason());
        assertInstanceOf(InWorldPollResult.Pending.class, SessionControlSupport.classifyInWorldPoll(null, null));
        assertInstanceOf(InWorldPollResult.Pending.class, SessionControlSupport.classifyInWorldPoll(Map.of("player", false), null));

        var progress = new SessionControlSupport.InWorldWaitProgress();
        Map<String, Object> inWorld = Map.of("player", Map.of("x", 0));
        assertInstanceOf(InWorldPollResult.Pending.class, SessionControlSupport.stepInWorldWait(progress, true, inWorld, null));
        assertInstanceOf(InWorldPollResult.Pending.class, SessionControlSupport.stepInWorldWait(progress, true, null, null));
        assertInstanceOf(InWorldPollResult.Pending.class, SessionControlSupport.stepInWorldWait(progress, true, Map.of("world", Map.of()), null));
        assertInstanceOf(InWorldPollResult.Joined.class, SessionControlSupport.stepInWorldWait(progress, true, inWorld, null));
        assertInstanceOf(InWorldPollResult.Failed.class, SessionControlSupport.stepInWorldWait(new SessionControlSupport.InWorldWaitProgress(), true, Map.of(), Map.of("type", "DisconnectedScreen")));
    }

    @Test
    void parsesOnlyOneDistinctPositiveListeningPid() {
        assertEquals(4242L, SessionControlSupport.parseListeningPid(" 4242 \r\n4242\r\n"));
        assertNull(SessionControlSupport.parseListeningPid(""));
        assertNull(SessionControlSupport.parseListeningPid("4242\n5151\n"));
        assertNull(SessionControlSupport.parseListeningPid("4242\nwarning\n"));
        assertNull(SessionControlSupport.parseListeningPid("-1\n"));
        assertNull(SessionControlSupport.parseListeningPid("0\n"));
    }

    @Test
    void matchesGameDirectoryFirstThenVersionAndNeverGuessesWithoutComparableIdentity() {
        SessionInfo matchingDirectory = sessionInfo("1.19", GAME_DIRECTORY);
        SessionInfo noDirectory = sessionInfo("1.21.11", null);
        var expected = new SessionControlSupport.ExpectedInstance(Optional.of(new MinecraftVersion("1.21.11")), Optional.of(GAME_DIRECTORY));

        assertTrue(SessionControlSupport.instanceMatches(matchingDirectory, expected));
        assertTrue(SessionControlSupport.instanceMatches(noDirectory, expected));
        assertFalse(SessionControlSupport.instanceMatches(sessionInfo("1.19", null), expected));
        assertFalse(SessionControlSupport.instanceMatches(sessionInfo("1.21.11", null), new SessionControlSupport.ExpectedInstance(Optional.empty(), Optional.of(GAME_DIRECTORY))));
        assertTrue(SessionControlSupport.instanceMatches(sessionInfo("anything", null), SessionControlSupport.ExpectedInstance.none()));
        assertTrue(WaitForBridgeArguments.from(new WaitForBridgeWireArguments("", null)).expectedVersion().isEmpty());
        assertEquals(new MinecraftVersion("1.21.11"), WaitForBridgeArguments.from(new WaitForBridgeWireArguments("1.21.11", null)).expectedVersion().orElseThrow());
    }

    @Test
    void scansDocumentedPortsPlusOneValidConfiguredOutOfRangePort() {
        assertEquals(List.of(9999, 9876, 9877, 9878, 9879, 9880, 9881, 9882, 9883, 9884, 9885, 9886), bridgePorts(Map.of("DEBUGBRIDGE_PORT", "9999")));
        assertEquals(List.of(9876, 9877, 9878, 9879, 9880, 9881, 9882, 9883, 9884, 9885, 9886), bridgePorts(Map.of("DEBUGBRIDGE_PORT", "65536")));
        assertEquals(List.of(9876, 9877, 9878, 9879, 9880, 9881, 9882, 9883, 9884, 9885, 9886), bridgePorts(Map.of("DEBUGBRIDGE_PORT", "9999.5")));
    }

    @Test
    void cancellationPreventsAComposedSideEffectFromStarting() {
        var first = new CompletableFuture<Integer>();
        var invoked = new AtomicBoolean();
        CompletableFuture<Integer> composed = SessionControlSupport.composeCancellable(first, value -> {
            invoked.set(true);
            return CompletableFuture.completedFuture(value + 1);
        }).toCompletableFuture();

        assertTrue(composed.cancel(true));
        first.complete(1);

        assertFalse(invoked.get());
        assertTrue(first.isCancelled());
    }

    @Test
    void portCloseFallbackAndProcessClassificationAreConservative() throws Exception {
        try (var harness = new BridgeTestHarness(McpJsonDefaults.getMapper(), new AppEnvironment(Map.of()), (_, request) -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id())));
             ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var support = new SessionControlSupport(harness.session(), new AppEnvironment(Map.of()), scheduler, System::currentTimeMillis, _ -> CompletableFuture.completedFuture(false), _ -> CompletableFuture.completedFuture(null));
            ClientExitResult result = support.waitForClientExit(9876, null, BigDecimal.valueOf(2), Cancellation.none()).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(new ClientExitResult.Exited(false), result);
        }
        assertTrue(SessionControlSupport.processAlive(ProcessHandle.current().pid()));
        assertFalse(SessionControlSupport.processAlive(Long.MAX_VALUE));
    }

    @Test
    void bridgeDeadlineWaitsForMismatchRecordingBeforePublishingTheTimeout() throws Exception {
        AtomicReference<CompletableFuture<BridgeResponse>> response = new AtomicReference<>();
        var notes = new BlockingNoteList();
        try (var harness = new BridgeTestHarness(McpJsonDefaults.getMapper(), new AppEnvironment(Map.of()), (_, _) -> {
            CompletableFuture<BridgeResponse> pending = new CompletableFuture<>();
            response.set(pending);
            return pending;
        }); var scheduler = new CapturingScheduler()) {
            var support = new SessionControlSupport(harness.session(), new AppEnvironment(Map.of()), scheduler, System::currentTimeMillis, _ -> CompletableFuture.completedFuture(false), _ -> CompletableFuture.completedFuture(null));
            var expected = new SessionControlSupport.ExpectedInstance(Optional.of(new MinecraftVersion("different")), Optional.empty());
            CompletableFuture<SessionControlSupport.FoundBridge> wait = support.waitForBridge(expected, BigDecimal.TEN, notes, Cancellation.none()).toCompletableFuture();

            CompletableFuture<BridgeResponse> pending = response.get();
            assertNotNull(pending);
            Thread mismatch = Thread.ofPlatform().start(() -> pending.complete(RuntimeContractFixtures.status("req_1")));
            assertTrue(notes.addEntered.await(1, TimeUnit.SECONDS));

            ScheduledFuture<?> scheduledDeadline = scheduler.scheduled.poll(1, TimeUnit.SECONDS);
            assertNotNull(scheduledDeadline);
            ManualScheduledFuture deadline = assertInstanceOf(ManualScheduledFuture.class, scheduledDeadline);
            Thread deadlineThread = Thread.ofPlatform().start(deadline);
            assertTrue(deadline.runStarted.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> deadline.get(100, TimeUnit.MILLISECONDS));
            assertFalse(wait.isDone());

            notes.releaseAdd.countDown();
            mismatch.join();
            deadlineThread.join();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> wait.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("Other instances answered: port 9876"));
            assertEquals(1, notes.size());
        }
    }

    @Test
    @SuppressWarnings("ExtractMethodRecommender")
    void scriptLoggerUsesNodePathsCompactJsonlStatsAndRotation(@TempDir Path temporary) throws Exception {
        var mapper = McpJsonDefaults.getMapper();
        var diagnostics = new java.util.ArrayList<String>();
        var logger = new ScriptLogger(temporary, mapper, diagnostics::add, () -> false, () -> 1234L);
        logger.log(new ScriptLogger.ScriptLogEntry(Instant.parse("2026-07-28T00:00:00Z"), true, "return 1", true, 1, "ok", null, Duration.ofMillis(5)), false);
        logger.log(new ScriptLogger.ScriptLogEntry(Instant.parse("2026-07-28T00:00:01Z"), false, "badName", false, null, null, "Failure at line 12:34: 'badName'", Duration.ofMillis(7)), false);
        logger.log(new ScriptLogger.ScriptLogEntry(Instant.parse("2026-07-28T00:00:02Z"), true, "return null", true, null, "", null, Duration.ofMillis(2)), false);
        logger.log(new ScriptLogger.ScriptLogEntry(Instant.parse("2026-07-28T00:00:03Z"), true, "no result", false, null, "", null, Duration.ofMillis(3)), false);

        List<String> all = Files.readAllLines(logger.allLogPath(), StandardCharsets.UTF_8);
        List<String> errors = Files.readAllLines(logger.errorsLogPath(), StandardCharsets.UTF_8);
        assertEquals(4, all.size());
        assertEquals(1, errors.size());
        assertFalse(all.getFirst().contains("\"error\""));
        assertFalse(all.getFirst().contains("\n"));
        assertTrue(all.get(2).contains("\"result\":null"));
        assertFalse(all.get(3).contains("\"result\""));
        assertEquals("Failure at line 12:34: 'badName'", logger.recentErrors(20).getFirst().error());
        ScriptLogger.ScriptErrorStat stat = logger.errorStats().getFirst();
        assertEquals("Failure at line N:N: '...'", stat.error());
        assertEquals(1, stat.count());
        assertEquals(List.of("badName"), stat.examples());

        Files.write(logger.allLogPath(), new byte[(int) ScriptLogger.MAX_LOG_BYTES + 1]);
        logger.rotateIfNeeded();
        assertFalse(Files.exists(logger.allLogPath()));
        assertTrue(Files.exists(logger.logDirectory().resolve("all.1234.jsonl")));
        assertTrue(diagnostics.isEmpty());

        // The Linux dataDirectory layout is verifiable on any POSIX host.
        assertEquals(Path.of("/home/test/.local/share/mcdev-mcp"), ScriptLogger.dataDirectory("Linux", new AppEnvironment(Map.of()), Path.of("/home/test")));

        // The Windows dataDirectory branch is only reachable in production when the
        // host OS is actually Windows, where java.nio.Path uses backslash separators.
        // On a POSIX CI runner the expected literal form (C:\\Local\\mcdev-mcp\\Data)
        // cannot be produced, so only assert it when running on Windows.
        var windows = new AppEnvironment(Map.of("LOCALAPPDATA", "C:\\Local"));
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"),
                "Windows-local dataDirectory layout only verifiable on a Windows host");
        assertEquals(Path.of("C:\\Local\\mcdev-mcp\\Data"), ScriptLogger.dataDirectory("Windows 11", windows, Path.of("C:\\Home")));
    }

    private static List<Integer> bridgePorts(Map<String, String> environment) {
        try (var harness = new BridgeTestHarness(McpJsonDefaults.getMapper(), new AppEnvironment(environment), (_, request) -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id())));
             ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            return new SessionControlSupport(harness.session(), new AppEnvironment(environment), scheduler).bridgePortRange();
        }
    }

    private static SessionInfo sessionInfo(String version, Path gameDirectory) {
        return new SessionInfo(9876, new MinecraftVersion(version), BridgeMappingStatus.MOJANG, false, 0, Optional.ofNullable(gameDirectory), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static final class BlockingNoteList extends AbstractList<String> {
        private final List<String> notes = new ArrayList<>();
        private final CountDownLatch addEntered = new CountDownLatch(1);
        private final CountDownLatch releaseAdd = new CountDownLatch(1);

        @Override
        public String get(int index) {
            return notes.get(index);
        }

        @Override
        public int size() {
            return notes.size();
        }

        @Override
        public boolean add(String note) {
            addEntered.countDown();
            try {
                if (!releaseAdd.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release note recording");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return notes.add(note);
        }
    }

    @SuppressWarnings("NullableProblems")
    private static final class CapturingScheduler extends ScheduledThreadPoolExecutor {
        private final BlockingQueue<ScheduledFuture<?>> scheduled = new LinkedBlockingQueue<>();

        private CapturingScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            var future = new ManualScheduledFuture(command);
            scheduled.add(future);
            return future;
        }
    }

    @SuppressWarnings("NullableProblems")
    private static final class ManualScheduledFuture extends FutureTask<Void> implements ScheduledFuture<Void> {
        private final CountDownLatch runStarted = new CountDownLatch(1);

        private ManualScheduledFuture(Runnable command) {
            super(command, null);
        }

        @Override
        public void run() {
            runStarted.countDown();
            super.run();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return other == this ? 0 : Integer.compare(System.identityHashCode(this), System.identityHashCode(other));
        }
    }
}
