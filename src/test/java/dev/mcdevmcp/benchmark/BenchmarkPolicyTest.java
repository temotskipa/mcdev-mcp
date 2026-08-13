package dev.mcdevmcp.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkPolicyTest {
    @Test
    void prefersJava26OnlyAfterThreeComparableRunsMeetEveryThreshold() {
        BenchmarkDecision decision = BenchmarkPolicy.evaluate(List.of(run(10, 1.06d, 1.07d, 1.05d), run(11, 1.05d, 1.06d, 1.03d), run(12, 1.07d, 1.08d, 1.08d)));
        assertTrue(decision.preferJava26());
        assertEquals(List.of(), decision.reasons());
    }

    @Test
    void rejectsWrongRunCountMachineSequenceMetricsAndThresholdsWithStableReasons() {
        assertEquals(List.of("Expected exactly three comparison runs, got 2"), BenchmarkPolicy.evaluate(List.of(run(1, 1.1d, 1.1d, 1.0d), run(2, 1.1d, 1.1d, 1.0d))).reasons());
        BenchmarkComparisonRun mixedMachine = new BenchmarkComparisonRun(3, "other", result(25, 100, 100, 100, 100), result(26, 106, 106, 105, 105));
        BenchmarkDecision decision = BenchmarkPolicy.evaluate(List.of(run(1, 1.06d, 1.06d, 1.0d), run(3, 0.97d, 0.97d, 1.11d), mixedMachine));
        assertFalse(decision.preferJava26());
        assertEquals(List.of("Workflow run numbers must be consecutive", "All comparison runs must use the same machine ID", "Workflow run numbers must be consecutive"), decision.reasons());
    }

    @Test
    void rejectsMissingOrInvalidMeasurements() {
        BenchmarkComparisonRun invalid = new BenchmarkComparisonRun(1, "machine", result(25, 100, 100, 100, 100), new BenchmarkResult(26, "vendor", "", Double.NaN, 0, 0, 0));
        BenchmarkDecision decision = BenchmarkPolicy.evaluate(List.of(invalid, run(2, 1.1d, 1.1d, 1.0d), run(3, 1.1d, 1.1d, 1.0d)));
        assertEquals(List.of("Java 26 index throughput must be positive for workflow run 1", "Java 26 callgraph throughput must be positive for workflow run 1", "Java 26 index RSS must be positive for workflow run 1", "Java 26 callgraph RSS must be positive for workflow run 1"), decision.reasons());
    }

    @Test
    void usesOneGeometricMeanAcrossTheSixThroughputRatios() {
        BenchmarkDecision decision = BenchmarkPolicy.evaluate(List.of(run(1, 1.04d, 1.08d, 1.0d), run(2, 1.04d, 1.08d, 1.0d), run(3, 1.04d, 1.08d, 1.0d)));
        assertTrue(decision.preferJava26());
    }

    @Test
    void policyMainWritesNeutralDecisionWithoutFailing() throws Exception {
        Path input = Files.createTempFile("benchmark-runs", ".json");
        Files.write(input, io.modelcontextprotocol.json.McpJsonDefaults.getMapper().writeValueAsBytes(List.of(run(1, 1.0d, 1.0d, 1.0d))));
        BenchmarkPolicyMain.main(new String[]{"--input", input.toString()});
    }

    private static BenchmarkComparisonRun run(long number, double indexRatio, double callgraphRatio, double rssRatio) {
        return new BenchmarkComparisonRun(number, "machine", result(25, 100, 100, 1000, 1000), result(26, 100 * indexRatio, 100 * callgraphRatio, Math.round(1000 * rssRatio), Math.round(1000 * rssRatio)));
    }

    private static BenchmarkResult result(int feature, double index, double callgraph, long indexRss, long callgraphRss) {
        return new BenchmarkResult(feature, "vendor", "", index, callgraph, indexRss, callgraphRss);
    }
}
