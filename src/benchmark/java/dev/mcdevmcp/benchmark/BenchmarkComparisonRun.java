package dev.mcdevmcp.benchmark;

/**
 * A counterbalanced Java 25/26 pair captured by one benchmark workflow run.
 */
public record BenchmarkComparisonRun(long workflowRunNumber, String machineId, BenchmarkResult java25, BenchmarkResult java26) {
}
