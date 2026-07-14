package dev.mcdevmcp.analysis.index;

import java.time.Duration;
import java.util.Objects;

public record IndexSummary(int packages, int types, int fields, int methods, int parameters, Duration elapsed) {
    public IndexSummary {
        if (packages < 0 || types < 0 || fields < 0 || methods < 0 || parameters < 0) {
            throw new IllegalArgumentException("Index counts must not be negative");
        }
        Objects.requireNonNull(elapsed, "elapsed");
    }
}
