package dev.mcdevmcp.support;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public record AppEnvironment(Map<String, String> values) {
    public AppEnvironment {
        values = Map.copyOf(values);
    }
    
    public static AppEnvironment system() {
        return new AppEnvironment(System.getenv());
    }
    
    public Optional<String> value(String name) {
        return Optional.ofNullable(values.get(name));
    }
    
    public boolean isTruthy(String name) {
        return value(name).map(value -> value.toLowerCase(Locale.ROOT)).map(value -> value.equals("1") || value.equals("true")).orElse(false);
    }
    
    public Optional<Path> debugLogPath() {
        return value("MCDEV_MCP_DEBUG_LOG").filter(value -> !value.isEmpty()).filter(value -> !value.equals("off")).map(value -> value.equals("on") ? Path.of("/tmp/mcdev-debug.log") : Path.of(value));
    }
    
    public int indexThreads(int availableProcessors) {
        int maximum = Math.max(1, availableProcessors);
        return value("MCDEV_INDEX_THREADS").flatMap(this::positiveInteger).map(value -> Math.min(value, maximum)).orElse(maximum);
    }
    
    private Optional<Integer> positiveInteger(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
