package dev.mcdevmcp.tools.runtime;

enum ScriptLogMode {
    ERRORS, STATS, PATHS;

    static ScriptLogMode fromWire(String value) {
        return switch (value == null || value.isEmpty() ? "errors" : value) {
            case "errors" -> ERRORS;
            case "stats" -> STATS;
            case "paths" -> PATHS;
            default -> throw new IllegalArgumentException("'mode' must be one of errors, stats, or paths");
        };
    }
}
