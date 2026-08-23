package dev.mcdevmcp.benchmark;

import java.util.List;
import java.util.Objects;

/**
 * Result of evaluating the published Java runtime preference policy.
 */
public record BenchmarkDecision(boolean preferJava26, List<String> reasons) {
    public BenchmarkDecision {
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
    }
}
