package dev.mcdevmcp.benchmark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Conservative three-run policy for documenting Java 26 as the preferred runtime.
 */
public final class BenchmarkPolicy {
    private static final double MINIMUM_GEOMETRIC_MEAN_THROUGHPUT_RATIO = 1.05d;
    private static final double MINIMUM_SINGLE_RUN_THROUGHPUT_RATIO = 0.98d;
    private static final double MAXIMUM_RSS_RATIO = 1.10d;

    private BenchmarkPolicy() {
    }

    public static BenchmarkDecision evaluate(List<BenchmarkComparisonRun> threeRuns) {
        List<String> reasons = new ArrayList<>();
        if (threeRuns == null) {
            return reject(reasons, "Benchmark comparison runs are required");
        }
        if (threeRuns.size() != 3) {
            return reject(reasons, "Expected exactly three comparison runs, got " + threeRuns.size());
        }
        List<BenchmarkComparisonRun> runs = new ArrayList<>(threeRuns);
        if (runs.stream().anyMatch(Objects::isNull)) {
            return reject(reasons, "Benchmark comparison runs must not contain null");
        }
        runs.sort(Comparator.comparingLong(BenchmarkComparisonRun::workflowRunNumber));
        String machine = runs.getFirst().machineId();
        if (machine == null || machine.isBlank()) {
            return reject(reasons, "Benchmark machine ID must be present");
        }
        for (int index = 0; index < runs.size(); index++) {
            BenchmarkComparisonRun run = runs.get(index);
            if (!machine.equals(run.machineId())) {
                reasons.add("All comparison runs must use the same machine ID");
            }
            if (index > 0 && run.workflowRunNumber() != runs.get(index - 1).workflowRunNumber() + 1) {
                reasons.add("Workflow run numbers must be consecutive");
            }
        }
        for (BenchmarkComparisonRun run : runs) {
            validateResult(reasons, run.workflowRunNumber(), "Java 25", run.java25());
            validateResult(reasons, run.workflowRunNumber(), "Java 26", run.java26());
        }
        if (!reasons.isEmpty()) {
            return new BenchmarkDecision(false, List.copyOf(reasons));
        }
        double throughputProduct = 1.0d;
        for (BenchmarkComparisonRun run : runs) {
            double indexRatio = run.java26().indexClassesPerSecond() / run.java25().indexClassesPerSecond();
            double callgraphRatio = run.java26().callEdgesPerSecond() / run.java25().callEdgesPerSecond();
            double indexRssRatio = (double) run.java26().indexPeakRssBytes() / run.java25().indexPeakRssBytes();
            double callgraphRssRatio = (double) run.java26().callgraphPeakRssBytes() / run.java25().callgraphPeakRssBytes();
            throughputProduct *= indexRatio;
            throughputProduct *= callgraphRatio;
            if (indexRatio < MINIMUM_SINGLE_RUN_THROUGHPUT_RATIO) {
                reasons.add("Java 26 index throughput ratio below 0.98 for workflow run " + run.workflowRunNumber());
            }
            if (callgraphRatio < MINIMUM_SINGLE_RUN_THROUGHPUT_RATIO) {
                reasons.add("Java 26 callgraph throughput ratio below 0.98 for workflow run " + run.workflowRunNumber());
            }
            if (indexRssRatio > MAXIMUM_RSS_RATIO) {
                reasons.add("Java 26 index RSS ratio above 1.10 for workflow run " + run.workflowRunNumber());
            }
            if (callgraphRssRatio > MAXIMUM_RSS_RATIO) {
                reasons.add("Java 26 callgraph RSS ratio above 1.10 for workflow run " + run.workflowRunNumber());
            }
        }
        if (Math.pow(throughputProduct, 1.0d / (runs.size() * 2)) < MINIMUM_GEOMETRIC_MEAN_THROUGHPUT_RATIO) {
            reasons.add("Java 26 geometric mean throughput ratio below 1.05");
        }
        return new BenchmarkDecision(reasons.isEmpty(), List.copyOf(reasons));
    }

    private static BenchmarkDecision reject(List<String> reasons, String reason) {
        reasons.add(reason);
        return new BenchmarkDecision(false, List.copyOf(reasons));
    }

    private static void validateResult(List<String> reasons, long runNumber, String runtime, BenchmarkResult result) {
        if (result == null) {
            reasons.add(runtime + " result is missing for workflow run " + runNumber);
            return;
        }
        if (result.javaFeature() != (runtime.equals("Java 25") ? 25 : 26)) {
            reasons.add(runtime + " feature must be " + (runtime.equals("Java 25") ? 25 : 26) + " for workflow run " + runNumber);
        }
        if (!isPositive(result.indexClassesPerSecond())) {
            reasons.add(runtime + " index throughput must be positive for workflow run " + runNumber);
        }
        if (!isPositive(result.callEdgesPerSecond())) {
            reasons.add(runtime + " callgraph throughput must be positive for workflow run " + runNumber);
        }
        if (result.indexPeakRssBytes() <= 0) {
            reasons.add(runtime + " index RSS must be positive for workflow run " + runNumber);
        }
        if (result.callgraphPeakRssBytes() <= 0) {
            reasons.add(runtime + " callgraph RSS must be positive for workflow run " + runNumber);
        }
    }

    private static boolean isPositive(double value) {
        return Double.isFinite(value) && value > 0.0d;
    }
}
