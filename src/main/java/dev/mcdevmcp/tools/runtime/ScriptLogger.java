package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class ScriptLogger {
    static final long MAX_LOG_BYTES = 10L * 1024 * 1024;

    // Keep rotated session-log files for at most 3 days so explicit session logging
    // cannot grow the data directory without bound on a long-lived server.
    private static final long ROTATION_RETENTION_MILLIS = 3L * 24 * 60 * 60 * 1000;

    private static final Pattern LINE_NUMBER = Pattern.compile("line (\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLON_NUMBER = Pattern.compile(":\\d+:");
    private static final Pattern QUOTED_VALUE = Pattern.compile("'[^']+'");

    private final Path logDirectory;
    private final Path allLog;
    private final Path errorsLog;
    private final McpJsonMapper mapper;
    private final Consumer<String> diagnostics;
    private final BooleanSupplier rotationSample;
    private final LongSupplier currentTimeMillis;
    private boolean rotating;

    ScriptLogger(Path dataDirectory, McpJsonMapper mapper, Consumer<String> diagnostics) {
        this(dataDirectory, mapper, diagnostics, () -> ThreadLocalRandom.current().nextDouble() < 0.01, System::currentTimeMillis);
    }

    ScriptLogger(Path dataDirectory, McpJsonMapper mapper, Consumer<String> diagnostics, BooleanSupplier rotationSample, LongSupplier currentTimeMillis) {
        logDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory").resolve("script-logs").normalize();
        allLog = logDirectory.resolve("all.jsonl");
        errorsLog = logDirectory.resolve("errors.jsonl");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.rotationSample = Objects.requireNonNull(rotationSample, "rotationSample");
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
    }

    static Path dataDirectory(String osName, AppEnvironment environment, Path home) {
        Objects.requireNonNull(osName, "osName");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(home, "home");
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            Path local = environment.value("LOCALAPPDATA").filter(value -> !value.isBlank()).map(Path::of).orElseGet(() -> home.resolve("AppData").resolve("Local"));
            return local.resolve("mcdev-mcp").resolve("Data");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return home.resolve("Library").resolve("Application Support").resolve("mcdev-mcp");
        }
        Path data = environment.value("XDG_DATA_HOME").filter(value -> !value.isBlank()).map(Path::of).orElseGet(() -> home.resolve(".local").resolve("share"));
        return data.resolve("mcdev-mcp");
    }

    private static String normalizeError(String error) {
        String normalized = LINE_NUMBER.matcher(error).replaceAll("line N");
        normalized = COLON_NUMBER.matcher(normalized).replaceAll(":N:");
        normalized = QUOTED_VALUE.matcher(normalized).replaceAll("'...'");
        return normalized.substring(0, Math.min(200, normalized.length()));
    }

    private static String baseName(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".jsonl") ? name.substring(0, name.length() - ".jsonl".length()) : name;
    }

    synchronized void log(ScriptLogEntry entry, boolean allowRotation) {
        append(allLog, entry);
        if (!entry.success()) {
            append(errorsLog, entry);
        }
        if (allowRotation && rotationSample.getAsBoolean()) {
            rotateIfNeeded();
        }
    }

    synchronized void rotateIfNeeded() {
        if (rotating) {
            return;
        }
        rotating = true;
        try {
            rotate(allLog);
            rotate(errorsLog);
        } finally {
            rotating = false;
        }
    }

    synchronized List<ScriptLogEntry> recentErrors(int limit) {
        if (limit <= 0 || !Files.exists(errorsLog)) {
            return List.of();
        }
        try {
            List<ScriptLogEntry> entries = new ArrayList<>();
            for (String line : Files.readAllLines(errorsLog, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    Object decoded = mapper.readValue(line.getBytes(StandardCharsets.UTF_8), Object.class);
                    if (decoded instanceof Map<?, ?> values) {
                        entries.add(ScriptLogWireEntry.fromJson(values).toDomain());
                    }
                } catch (IOException | RuntimeException ignored) {
                }
            }
            return List.copyOf(entries.subList(Math.max(0, entries.size() - limit), entries.size()));
        } catch (IOException exception) {
            return List.of();
        }
    }

    synchronized List<ScriptErrorStat> errorStats() {
        Map<String, MutableErrorStat> grouped = new LinkedHashMap<>();
        for (ScriptLogEntry entry : recentErrors(500)) {
            if (entry.error() == null) {
                continue;
            }
            String normalized = normalizeError(entry.error());
            MutableErrorStat stat = grouped.computeIfAbsent(normalized, _ -> new MutableErrorStat());
            stat.count++;
            stat.lastSeen = entry.timestamp();
            if (stat.examples.size() < 3 && !stat.examples.contains(entry.code())) {
                stat.examples.add(entry.code());
            }
        }
        return grouped.entrySet().stream().map(entry -> new ScriptErrorStat(entry.getKey(), entry.getValue().count, entry.getValue().lastSeen, List.copyOf(entry.getValue().examples))).sorted(Comparator.comparingInt(ScriptErrorStat::count).reversed()).toList();
    }

    Path allLogPath() {
        return allLog;
    }

    Path errorsLogPath() {
        return errorsLog;
    }

    Path logDirectory() {
        return logDirectory;
    }

    private void append(Path path, ScriptLogEntry entry) {
        try {
            Files.createDirectories(logDirectory);
            Files.write(path, jsonLine(entry), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException exception) {
            diagnostics.accept("[ScriptLogger] Failed to write log: " + exception);
        }
    }

    private byte[] jsonLine(ScriptLogEntry entry) throws IOException {
        ScriptLogWireEntry wire = ScriptLogWireEntry.fromDomain(entry);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("timestamp", wire.timestamp());
        values.put("success", wire.success());
        values.put("code", wire.code());
        if (wire.resultPresent()) {
            values.put("result", wire.result());
        }
        if (wire.output() != null) {
            values.put("output", wire.output());
        }
        if (wire.error() != null) {
            values.put("error", wire.error());
        }
        values.put("duration_ms", wire.duration_ms());
        byte[] json = mapper.writeValueAsBytes(values);
        byte[] line = java.util.Arrays.copyOf(json, json.length + 1);
        line[json.length] = '\n';
        return line;
    }

    private void rotate(Path live) {
        try {
            if (!Files.exists(live) || Files.size(live) <= MAX_LOG_BYTES) {
                return;
            }
            String baseName = baseName(live);
            Path rotated = live.resolveSibling(baseName + "." + currentTimeMillis.getAsLong() + ".jsonl");
            Files.move(live, rotated);
            cleanOldRotations(live);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private void cleanOldRotations(Path live) throws IOException {
        long now = currentTimeMillis.getAsLong();
        String baseName = baseName(live);
        try (Stream<Path> paths = Files.list(logDirectory)) {
            List<Path> rotations = paths.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(baseName + ".") && name.endsWith(".jsonl") && !path.equals(live);
            }).toList();
            long cutoff = now - ROTATION_RETENTION_MILLIS;
            for (Path old : rotations) {
                Long timestamp = rotationTimestamp(old.getFileName().toString(), baseName.length());
                if (timestamp != null && timestamp < cutoff) {
                    try {
                        Files.deleteIfExists(old);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    // Parses the epoch-millis suffix from a rotation file name such as "all.1700000000000.jsonl".
    // The millis start immediately after the "<base>." prefix.
    private static Long rotationTimestamp(String name, int baseNameLength) {
        int start = baseNameLength + 1;
        int end = name.length() - ".jsonl".length();
        if (end <= start) {
            return null;
        }
        try {
            return Long.parseLong(name.substring(start, end));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    record ScriptLogEntry(Instant timestamp, boolean success, String code, boolean resultPresent, Object result, String output, String error, Duration duration) {
        ScriptLogEntry {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(duration, "duration");
        }

        static ScriptLogEntry completed(boolean success, String code, boolean resultPresent, Object result, String output, String error, Duration duration) {
            return new ScriptLogEntry(Instant.now(), success, code, resultPresent, result, output, error, duration);
        }

        static ScriptLogEntry failed(String code, String error, Duration duration) {
            return new ScriptLogEntry(Instant.now(), false, code, false, null, null, error, duration);
        }
    }

    record ScriptErrorStat(String error, int count, Instant lastSeen, List<String> examples) {
    }

    private static final class MutableErrorStat {
        private final List<String> examples = new ArrayList<>();
        private int count;
        private Instant lastSeen;
    }
}
