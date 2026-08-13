package dev.mcdevmcp.benchmark;

import dev.mcdevmcp.analysis.callgraph.CallgraphRequest;
import dev.mcdevmcp.analysis.callgraph.CallgraphScanner;
import dev.mcdevmcp.analysis.callgraph.CallgraphSummary;
import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.analysis.index.IndexSummary;
import dev.mcdevmcp.analysis.index.SourceIndexer;
import dev.mcdevmcp.analysis.index.SourceRoot;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.ProgressSink;
import io.modelcontextprotocol.json.McpJsonDefaults;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the index and callgraph macrobenchmarks in independent child JVMs.
 */
public final class AnalysisBenchmarkMain {
    static final int WARMUP_RUNS = 1;
    static final int MEASURED_RUNS = 5;
    static final int CHILD_INVOCATIONS = (WARMUP_RUNS + MEASURED_RUNS) * 2;
    static final Duration DEFAULT_CHILD_DEADLINE = Duration.ofMinutes(30);
    static final int MAXIMUM_CHILD_OUTPUT_BYTES = 1024 * 1024;
    private static final String CHILD_COMMAND = "--child";
    private static final ProgressSink SILENT_PROGRESS = (_, _, _) -> {
    };

    private AnalysisBenchmarkMain() {
    }

    @SuppressWarnings("UnnecessaryModifier")
    public static void main(String[] arguments) throws Exception {
        if (arguments.length > 0 && CHILD_COMMAND.equals(arguments[0])) {
            runChild(ChildArguments.parse(Arrays.copyOfRange(arguments, 1, arguments.length)));
            return;
        }
        runParent(Arguments.parse(arguments), new JvmChildProcessRunner(DEFAULT_CHILD_DEADLINE));
    }

    static BenchmarkReport runParent(Arguments arguments, ChildProcessRunner processRunner) throws Exception {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(processRunner, "processRunner");
        ValidatedPaths paths = validatePaths(arguments);
        Files.createDirectories(paths.outputRoot());

        String sourceHash = sha256Tree(paths.sourceRoot());
        String jarHash = sha256(paths.remappedJar());
        String serverJarHash = sha256(paths.serverJar());
        List<BenchmarkMeasurement> measurements = new ArrayList<>(MEASURED_RUNS);
        BenchmarkRuntimeMetadata measuredRuntime = null;
        for (int sequence = 0; sequence < WARMUP_RUNS + MEASURED_RUNS; sequence++) {
            boolean measured = sequence >= WARMUP_RUNS;
            Path sequenceRoot = paths.outputRoot().resolve(measured ? "measured-" + sequence : "warmup");
            deleteTree(sequenceRoot);
            Files.createDirectories(sequenceRoot);
            BenchmarkChildMeasurement index = processRunner.run(childCommand(arguments, paths, BenchmarkPhase.INDEX, sequenceRoot.resolve("index")));
            BenchmarkChildMeasurement callgraph = processRunner.run(childCommand(arguments, paths, BenchmarkPhase.CALLGRAPH, sequenceRoot.resolve("callgraph")));
            if (index.phase() != BenchmarkPhase.INDEX || callgraph.phase() != BenchmarkPhase.CALLGRAPH) {
                throw new IOException("Benchmark child returned a result for the wrong phase");
            }
            if (!index.runtime().equals(callgraph.runtime())) {
                throw new IOException("Index and callgraph benchmark children used different runtimes");
            }
            if (measuredRuntime == null) {
                measuredRuntime = index.runtime();
            }
            else if (!measuredRuntime.equals(index.runtime())) {
                throw new IOException("Benchmark child runtime changed between phases");
            }
            if (measured) {
                measurements.add(BenchmarkMeasurement.of(index, callgraph));
            }
        }

        if (!sourceHash.equals(sha256Tree(paths.sourceRoot())) || !jarHash.equals(sha256(paths.remappedJar())) || !serverJarHash.equals(sha256(paths.serverJar()))) {
            throw new IOException("Immutable benchmark inputs changed while measurements were running");
        }
        BenchmarkMedians medians = BenchmarkMedians.of(measurements);
        BenchmarkRuntimeMetadata runtime = Objects.requireNonNull(measuredRuntime, "measuredRuntime");
        BenchmarkResult result = new BenchmarkResult(runtime.javaFeature(), runtime.vendor(), runtime.vmFlags(), medians.indexClassesPerSecond(), medians.callEdgesPerSecond(), medians.indexPeakRssBytes(), medians.callgraphPeakRssBytes());
        BenchmarkReport report = new BenchmarkReport(1, arguments.runId(), arguments.machineId(), Instant.now(), sourceHash, jarHash, serverJarHash, runtime, result, medians, List.copyOf(measurements));
        Path reportPath = paths.outputRoot().resolve("benchmark-" + safeFileComponent(arguments.runId()) + ".json");
        Files.write(reportPath, McpJsonDefaults.getMapper().writeValueAsBytes(report));
        return report;
    }

    private static ChildCommand childCommand(Arguments arguments, ValidatedPaths paths, BenchmarkPhase phase, Path childOutput) {
        return new ChildCommand(currentJavaExecutable(), System.getProperty("java.class.path"), arguments.minecraftVersion(), paths.sourceRoot(), paths.remappedJar(), childOutput, paths.productionCacheRoot(), arguments.workers(), phase, arguments.garbageCollector());
    }

    private static void runChild(ChildArguments arguments) throws Exception {
        validateChildPaths(arguments);
        Files.createDirectories(arguments.outputRoot());
        forceGarbageCollection();
        GcSnapshot before = GcSnapshot.current();
        long started = System.nanoTime();
        BenchmarkWorkCounts counts = switch (arguments.phase()) {
            case INDEX -> runIndex(arguments);
            case CALLGRAPH -> runCallgraph(arguments);
        };
        long elapsedNanos = System.nanoTime() - started;
        GcSnapshot after = GcSnapshot.current();
        BenchmarkChildMeasurement result = new BenchmarkChildMeasurement(arguments.phase(), counts.units(), elapsedNanos, peakRssBytes(), Math.subtractExact(after.collections(), before.collections()), Math.subtractExact(after.collectionTimeMillis(), before.collectionTimeMillis()), counts, BenchmarkRuntimeMetadata.current());
        System.out.write(McpJsonDefaults.getMapper().writeValueAsBytes(result));
        System.out.write('\n');
    }

    static BenchmarkWorkCounts runIndex(ChildArguments arguments) throws Exception {
        IndexSummary summary = new SourceIndexer().build(new IndexRequest(arguments.minecraftVersion(), List.of(new SourceRoot(SourceNamespace.MINECRAFT, Optional.empty(), arguments.sourceRoot())), arguments.remappedJar(), List.of(), arguments.outputRoot().resolve("symbols.mv.db"), arguments.workers(), SILENT_PROGRESS, Cancellation.none()));
        return new BenchmarkWorkCounts(summary.types(), summary.packages(), summary.types(), summary.fields(), summary.methods(), summary.parameters(), 0, 0, 0);
    }

    static BenchmarkWorkCounts runCallgraph(ChildArguments arguments) throws Exception {
        CallgraphSummary summary = new CallgraphScanner().scan(new CallgraphRequest(arguments.minecraftVersion(), arguments.remappedJar(), arguments.outputRoot().resolve("bundle"), arguments.workers(), SILENT_PROGRESS, Cancellation.none()));
        return new BenchmarkWorkCounts(summary.edges(), 0, 0, 0, 0, 0, summary.classes(), summary.methods(), summary.edges());
    }

    private static ValidatedPaths validatePaths(Arguments arguments) throws IOException {
        Path source = canonicalSourceRoot(arguments.sourceRoot());
        Path jar = canonicalRemappedJar(arguments.remappedJar());
        Path serverJar = canonicalServerJar(arguments.serverJar());
        Path output = canonicalProspectivePath(arguments.outputRoot());
        Path productionCache = canonicalProspectivePath(arguments.productionCacheRoot());
        rejectOutputOverlap(output, source, "immutable source root");
        rejectOutputOverlap(output, jar, "immutable remapped JAR");
        rejectOutputOverlap(output, serverJar, "immutable server JAR");
        rejectOutputOverlap(output, productionCache, "production cache root");
        return new ValidatedPaths(source, jar, serverJar, output, productionCache);
    }

    private static void validateChildPaths(ChildArguments arguments) throws IOException {
        Path source = canonicalSourceRoot(arguments.sourceRoot());
        Path jar = canonicalRemappedJar(arguments.remappedJar());
        Path output = canonicalProspectivePath(arguments.outputRoot());
        Path productionCache = canonicalProspectivePath(arguments.productionCacheRoot());
        rejectOutputOverlap(output, source, "immutable source root");
        rejectOutputOverlap(output, jar, "immutable remapped JAR");
        rejectOutputOverlap(output, productionCache, "production cache root");
    }

    private static Path canonicalSourceRoot(Path path) throws IOException {
        Path normalized = Objects.requireNonNull(path, "sourceRoot").toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new IOException("Source root must be an immutable non-symbolic directory: " + normalized);
        }
        return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static Path canonicalRemappedJar(Path path) throws IOException {
        Path normalized = Objects.requireNonNull(path, "remappedJar").toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new IOException("Remapped JAR must be an immutable non-symbolic regular file: " + normalized);
        }
        return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static Path canonicalServerJar(Path path) throws IOException {
        Path normalized = Objects.requireNonNull(path, "serverJar").toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new IOException("Server JAR must be an immutable non-symbolic regular file: " + normalized);
        }
        return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static Path canonicalProspectivePath(Path path) throws IOException {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Path existing = normalized;
        while (existing != null && Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("Unable to resolve benchmark path: " + normalized);
        }
        Path realAncestor = existing.toRealPath();
        return realAncestor.resolve(existing.relativize(normalized)).normalize();
    }

    private static void rejectOutputOverlap(Path output, Path protectedPath, String protectedLabel) {
        if (output.equals(protectedPath) || output.startsWith(protectedPath) || protectedPath.startsWith(output)) {
            throw new IllegalArgumentException("benchmark output root must not overlap " + protectedLabel);
        }
    }

    static long peakRssBytes() throws IOException {
        Path status = Path.of("/proc/self/status");
        if (!Files.isRegularFile(status, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Peak RSS measurement is unavailable on this runner; benchmark evidence must not be fabricated");
        }
        for (String line : Files.readAllLines(status, StandardCharsets.US_ASCII)) {
            if (line.startsWith("VmHWM:")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) {
                    break;
                }
                return Math.multiplyExact(Long.parseLong(parts[1]), 1024L);
            }
        }
        throw new IOException("Linux process status did not provide VmHWM peak RSS");
    }

    static String sha256Tree(Path root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)).sorted(Comparator.comparing(candidate -> root.relativize(candidate).toString().replace('\\', '/'))).toList()) {
                digest.update(root.relativize(path).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(path));
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file);
             OutputStream output = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
            input.transferTo(output);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void forceGarbageCollection() throws InterruptedException {
        System.gc();
        Thread.sleep(100L);
    }

    private static Path currentJavaExecutable() {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath().normalize();
    }

    private static String safeFileComponent(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void deleteTree(Path path) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path child : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(child);
            }
        }
    }

    interface ChildProcessRunner {
        BenchmarkChildMeasurement run(ChildCommand command) throws Exception;
    }

    static final class JvmChildProcessRunner implements ChildProcessRunner {
        private final Duration deadline;

        JvmChildProcessRunner(Duration deadline) {
            this.deadline = Objects.requireNonNull(deadline, "deadline");
            if (deadline.isZero() || deadline.isNegative()) {
                throw new IllegalArgumentException("deadline must be positive");
            }
        }

        @Override
        public BenchmarkChildMeasurement run(ChildCommand command) throws Exception {
            BenchmarkProcessOutput output = executeProcess(command.asProcessCommand(), deadline);
            if (output.standardOutputOverflowed() || output.standardErrorOverflowed()) {
                throw new IOException("Benchmark child output exceeded " + MAXIMUM_CHILD_OUTPUT_BYTES + " bytes: " + command.phase());
            }
            if (output.exitCode() != 0) {
                throw new IOException("Benchmark child failed for " + command.phase() + " with exit " + output.exitCode() + ": " + output.standardError());
            }
            try {
                return McpJsonDefaults.getMapper().readValue(output.standardOutput(), BenchmarkChildMeasurement.class);
            } catch (IOException exception) {
                throw new IOException("Benchmark child returned invalid JSON for " + command.phase() + ": " + output.standardOutput(), exception);
            }
        }
    }

    static BenchmarkProcessOutput executeProcess(List<String> command, Duration deadline) throws IOException, InterruptedException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(deadline, "deadline");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        if (deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("deadline must be positive");
        }
        Process process = new ProcessBuilder(command).start();
        AtomicReference<IOException> stdoutFailure = new AtomicReference<>();
        AtomicReference<IOException> stderrFailure = new AtomicReference<>();
        try (BoundedOutput stdout = new BoundedOutput(); BoundedOutput stderr = new BoundedOutput()) {
            Thread stdoutReader = Thread.ofVirtual().name("benchmark-child-stdout").start(() -> copy(process.getInputStream(), stdout, stdoutFailure));
            Thread stderrReader = Thread.ofVirtual().name("benchmark-child-stderr").start(() -> copy(process.getErrorStream(), stderr, stderrFailure));
            boolean exited;
            try {
                exited = process.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw exception;
            }
            if (!exited) {
                process.destroyForcibly();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    throw new IOException("Benchmark child did not terminate after exceeding deadline " + deadline);
                }
            }
            joinReader(stdoutReader);
            joinReader(stderrReader);
            IOException readFailure = stdoutFailure.get() != null ? stdoutFailure.get() : stderrFailure.get();
            if (readFailure != null) {
                throw new IOException("Unable to capture benchmark child output", readFailure);
            }
            if (!exited) {
                throw new IOException("Benchmark child exceeded deadline " + deadline);
            }
            return new BenchmarkProcessOutput(process.exitValue(), stdout.text(), stderr.text(), stdout.overflowed(), stderr.overflowed());
        }
    }

    private static void joinReader(Thread reader) throws IOException, InterruptedException {
        reader.join(Duration.ofSeconds(10));
        if (reader.isAlive()) {
            reader.interrupt();
            throw new IOException("Benchmark child output reader did not terminate");
        }
    }

    private static void copy(InputStream input, BoundedOutput output, AtomicReference<IOException> failure) {
        try (input) {
            input.transferTo(output);
        } catch (IOException exception) {
            failure.compareAndSet(null, exception);
        }
    }

    record ChildCommand(Path javaExecutable, String classpath, MinecraftVersion minecraftVersion, Path sourceRoot, Path remappedJar, Path outputRoot, Path productionCacheRoot, int workers, BenchmarkPhase phase, BenchmarkGarbageCollector garbageCollector) {
        ChildCommand {
            Objects.requireNonNull(javaExecutable, "javaExecutable");
            Objects.requireNonNull(classpath, "classpath");
            Objects.requireNonNull(minecraftVersion, "minecraftVersion");
            Objects.requireNonNull(sourceRoot, "sourceRoot");
            Objects.requireNonNull(remappedJar, "remappedJar");
            Objects.requireNonNull(outputRoot, "outputRoot");
            Objects.requireNonNull(productionCacheRoot, "productionCacheRoot");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(garbageCollector, "garbageCollector");
            if (workers < 1) {
                throw new IllegalArgumentException("workers must be positive");
            }
        }

        List<String> asProcessCommand() {
            return List.of(javaExecutable.toString(), "-Xmx4g", garbageCollector.jvmFlag(), "-cp", classpath, AnalysisBenchmarkMain.class.getName(), CHILD_COMMAND, "--minecraft-version", minecraftVersion.value(), "--source-root", sourceRoot.toString(), "--remapped-jar", remappedJar.toString(), "--output-root", outputRoot.toString(), "--production-cache-root", productionCacheRoot.toString(), "--workers", Integer.toString(workers), "--phase", phase.name());
        }
    }

    record Arguments(MinecraftVersion minecraftVersion, Path sourceRoot, Path remappedJar, Path serverJar, Path outputRoot, Path productionCacheRoot, int workers, String machineId, String runId, BenchmarkGarbageCollector garbageCollector) {
        Arguments {
            Objects.requireNonNull(minecraftVersion, "minecraftVersion");
            sourceRoot = normalize(sourceRoot, "sourceRoot");
            remappedJar = normalize(remappedJar, "remappedJar");
            serverJar = normalize(serverJar, "serverJar");
            outputRoot = normalize(outputRoot, "outputRoot");
            productionCacheRoot = normalize(productionCacheRoot, "productionCacheRoot");
            if (workers < 1) {
                throw new IllegalArgumentException("workers must be positive");
            }
            machineId = requireText(machineId, "machineId");
            runId = requireText(runId, "runId");
            Objects.requireNonNull(garbageCollector, "garbageCollector");
        }

        static Arguments parse(String[] values) {
            Options options = Options.parse(values, "benchmark");
            options.requireOnly("--minecraft-version", "--source-root", "--remapped-jar", "--server-jar", "--output-root", "--production-cache-root", "--workers", "--machine-id", "--run-id", "--gc");
            return new Arguments(new MinecraftVersion(options.required("--minecraft-version")), Path.of(options.required("--source-root")), Path.of(options.required("--remapped-jar")), Path.of(options.required("--server-jar")), Path.of(options.required("--output-root")), Path.of(options.required("--production-cache-root")), positiveWorkers(options.required("--workers")), options.required("--machine-id"), options.required("--run-id"), BenchmarkGarbageCollector.valueOf(options.required("--gc")));
        }
    }

    record ChildArguments(MinecraftVersion minecraftVersion, Path sourceRoot, Path remappedJar, Path outputRoot, Path productionCacheRoot, int workers, BenchmarkPhase phase) {
        private static ChildArguments parse(String[] values) {
            Options options = Options.parse(values, "benchmark child");
            options.requireOnly("--minecraft-version", "--source-root", "--remapped-jar", "--output-root", "--production-cache-root", "--workers", "--phase");
            return new ChildArguments(new MinecraftVersion(options.required("--minecraft-version")), Path.of(options.required("--source-root")).toAbsolutePath().normalize(), Path.of(options.required("--remapped-jar")).toAbsolutePath().normalize(), Path.of(options.required("--output-root")).toAbsolutePath().normalize(), Path.of(options.required("--production-cache-root")).toAbsolutePath().normalize(), positiveWorkers(options.required("--workers")), BenchmarkPhase.valueOf(options.required("--phase")));
        }
    }

    private record ValidatedPaths(Path sourceRoot, Path remappedJar, Path serverJar, Path outputRoot, Path productionCacheRoot) {
    }

    private record Options(Map<String, String> values) {
        private static Options parse(String[] arguments, String label) {
            if (arguments.length % 2 != 0) {
                throw new IllegalArgumentException("Expected --name value arguments for " + label);
            }
            Map<String, String> values = new HashMap<>();
            for (int index = 0; index < arguments.length; index += 2) {
                String name = arguments[index];
                if (!name.startsWith("--") || values.put(name, arguments[index + 1]) != null) {
                    throw new IllegalArgumentException("Invalid or duplicate " + label + " argument: " + name);
                }
            }
            return new Options(Map.copyOf(values));
        }

        private String required(String name) {
            return requireText(values.get(name), "Missing required argument " + name);
        }

        private void requireOnly(String... names) {
            List<String> allowed = List.of(names);
            for (String name : values.keySet()) {
                if (!allowed.contains(name)) {
                    throw new IllegalArgumentException("Unknown argument " + name);
                }
            }
            for (String name : allowed) {
                required(name);
            }
        }
    }

    private static int positiveWorkers(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 1) {
            throw new IllegalArgumentException("--workers must be positive");
        }
        return parsed;
    }

    private static Path normalize(Path path, String label) {
        return Objects.requireNonNull(path, label).toAbsolutePath().normalize();
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label);
        }
        return value;
    }

    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean overflowed;

        @Override
        public synchronized void write(int value) {
            if (bytes.size() < MAXIMUM_CHILD_OUTPUT_BYTES) {
                bytes.write(value);
            }
            else {
                overflowed = true;
            }
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public synchronized void write(byte[] buffer, int offset, int length) {
            int accepted = Math.min(length, MAXIMUM_CHILD_OUTPUT_BYTES - bytes.size());
            if (accepted > 0) {
                bytes.write(buffer, offset, accepted);
            }
            if (accepted < length) {
                overflowed = true;
            }
        }

        private synchronized String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }

        private synchronized boolean overflowed() {
            return overflowed;
        }
    }

    private record GcSnapshot(long collections, long collectionTimeMillis) {
        private static GcSnapshot current() {
            long collections = 0;
            long time = 0;
            for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
                collections = addNonNegative(collections, collector.getCollectionCount());
                time = addNonNegative(time, collector.getCollectionTime());
            }
            return new GcSnapshot(collections, time);
        }

        private static long addNonNegative(long total, long value) {
            return value < 0 ? total : Math.addExact(total, value);
        }
    }

}
