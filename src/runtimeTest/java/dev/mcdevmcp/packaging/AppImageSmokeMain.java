package dev.mcdevmcp.packaging;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import reactor.util.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Exercises a packaged application image the way an MCP client does: no JDK on the command line,
 * no flags. The first session must answer on a clean standard output and start the recording of
 * the startup cache; once the cache is published a second session must still answer.
 */
final class AppImageSmokeMain {
    private static final String APPLICATION = "mcdev-mcp";
    private static final String CACHE = APPLICATION + ".aot";
    private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CACHE_TIMEOUT = Duration.ofSeconds(180);
    private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();
    private static final TypeRef<Map<String, Object>> MESSAGE = new TypeRef<>() {
    };
    private static final List<String> EXCHANGE = List.of("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"app-image-smoke\",\"version\":\"1\"}}}", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");

    private AppImageSmokeMain() {
    }

    static void main(String[] arguments) throws Exception {
        Path packaged = Path.of(arguments[0]).toAbsolutePath().normalize();
        String version = arguments[1];
        Path work = Path.of(arguments[2]).toAbsolutePath().normalize();
        deleteTree(work);
        copyTree(packaged, work.resolve("image"));
        Path launcher = launcher(work.resolve("image"));
        Path cache = applicationDirectory(work.resolve("image")).resolve(CACHE);
        require(!Files.exists(cache), "The packaged image must not ship a startup cache: " + cache);
        require(version.equals(reportedVersion(work, launcher)), "The launcher does not report version " + version);

        try (var watcher = cache.getFileSystem().newWatchService()) {
            cache.getParent().register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            long first = session(work, launcher, "first");
            long deadline = System.nanoTime() + CACHE_TIMEOUT.toNanos();
            Path identity = cache.resolveSibling(CACHE + ".id");
            while (!Files.isRegularFile(cache) || !Files.isRegularFile(identity)) {
                long remaining = deadline - System.nanoTime();
                require(remaining > 0, "No startup cache was recorded within " + CACHE_TIMEOUT.toSeconds() + " seconds");
                var key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
                if (key != null) {
                    key.pollEvents();
                    key.reset();
                }
            }
            long second = session(work, launcher, "second");
            System.out.printf("Application image answered initialize after %d ms without a startup cache and %d ms with one.%n", first, second);
        }
    }

    private static Path launcher(Path image) {
        return Stream.of(image.resolve(APPLICATION).resolve(APPLICATION + ".exe"), image.resolve(APPLICATION).resolve("bin").resolve(APPLICATION), image.resolve(APPLICATION + ".app").resolve("Contents").resolve("MacOS").resolve(APPLICATION)).filter(Files::isRegularFile).findFirst().orElseThrow(() -> new IllegalStateException("No launcher found in " + image));
    }

    private static Path applicationDirectory(Path image) throws IOException {
        try (Stream<Path> files = Files.walk(image)) {
            return files.filter(file -> file.getFileName().toString().equals(APPLICATION + ".cfg")).findFirst().orElseThrow(() -> new IllegalStateException("No launcher configuration found in " + image)).getParent();
        }
    }

    private static ProcessBuilder command(Path work, Path launcher, String argument) {
        var builder = new ProcessBuilder(launcher.toString(), argument);
        var environment = builder.environment();
        environment.remove("JAVA_TOOL_OPTIONS");
        environment.remove("JDK_JAVA_OPTIONS");
        environment.put("LOCALAPPDATA", work.resolve("user").toString());
        environment.put("XDG_CACHE_HOME", work.resolve("user").toString());
        return builder;
    }

    private static String reportedVersion(Path work, Path launcher) throws Exception {
        Path captured = work.resolve("version.txt");
        try (Process process = command(work, launcher, "--version").redirectErrorStream(true).redirectOutput(captured.toFile()).start()) {
            require(process.waitFor(SESSION_TIMEOUT.toSeconds(), TimeUnit.SECONDS), "The launcher did not report its version in time");
            require(process.exitValue() == 0, "The launcher failed to report its version: " + Files.readString(captured));
        }
        return Files.readString(captured, StandardCharsets.UTF_8).strip();
    }

    @SuppressWarnings("preview")
    private static long session(Path work, Path launcher, String label) throws Exception {
        Path errors = work.resolve(label + "-stderr.txt");
        long started = System.nanoTime();
        long answered;
        try (Process process = command(work, launcher, "serve").redirectError(errors.toFile()).start()) {
            try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<Long>awaitAllSuccessfulOrThrow(), config -> config.withTimeout(SESSION_TIMEOUT))) {
                var exchange = scope.fork(() -> converse(process, started));
                scope.join();
                answered = exchange.get();
            } finally {
                if (process.isAlive() && !process.waitFor(SESSION_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
            require(process.exitValue() == 0, "The " + label + " session ended with exit code " + process.exitValue());
        }
        String reported = Files.readString(errors, StandardCharsets.UTF_8);
        require(reported.isBlank(), "The " + label + " session wrote to standard error: " + reported);
        return answered;
    }

    private static Long converse(Process process, long started) throws IOException {
        try (Writer writer = process.outputWriter(StandardCharsets.UTF_8); BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            for (String message : EXCHANGE) {
                writer.write(message);
                writer.write('\n');
            }
            writer.flush();
            Map<String, Object> initialized = JSON.readValue(requireLine(reader), MESSAGE);
            long answered = Duration.ofNanos(System.nanoTime() - started).toMillis();
            require(initialized.get("result") instanceof Map<?, ?>, "initialize did not succeed: " + initialized);
            Map<String, Object> listed = JSON.readValue(requireLine(reader), MESSAGE);
            require(listed.get("result") instanceof Map<?, ?> result && result.get("tools") instanceof List<?> tools && !tools.isEmpty(), "tools/list returned no tools: " + listed);
            return answered;
        }
    }

    private static String requireLine(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        require(line != null && line.startsWith("{"), "Standard output carried something other than a JSON-RPC message: " + line);
        return line;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path copy = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(file, copy, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            @NonNull
            public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            @NonNull
            public FileVisitResult postVisitDirectory(@NonNull Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
