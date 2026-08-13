package dev.mcdevmcp.benchmark;

import java.util.Objects;

/**
 * Typed child-JVM result used by the parent benchmark process.
 */
public record BenchmarkChildMeasurement(BenchmarkPhase phase, long units, long elapsedNanos, long peakRssBytes, long gcCollections, long gcTimeMillis, BenchmarkWorkCounts counts, BenchmarkRuntimeMetadata runtime) {
    public BenchmarkChildMeasurement {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(runtime, "runtime");
        if (units <= 0 || elapsedNanos <= 0 || peakRssBytes <= 0 || gcCollections < 0 || gcTimeMillis < 0) {
            throw new IllegalArgumentException("Benchmark child metrics are invalid");
        }
        if (units != counts.units()) {
            throw new IllegalArgumentException("Benchmark units do not match the work counts");
        }
    }

    public double throughputPerSecond() {
        return units / (elapsedNanos / 1_000_000_000.0d);
    }
}
