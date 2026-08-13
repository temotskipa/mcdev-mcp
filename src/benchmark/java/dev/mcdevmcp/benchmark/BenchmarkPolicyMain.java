package dev.mcdevmcp.benchmark;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Evaluates exactly three persisted comparison records without treating a neutral preference as infrastructure failure.
 */
public final class BenchmarkPolicyMain {
    private static final TypeRef<List<BenchmarkComparisonRun>> RUNS = new TypeRef<>() {
    };

    private BenchmarkPolicyMain() {
    }

    @SuppressWarnings("RedundantModifiers")
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2 || !"--input".equals(arguments[0])) {
            throw new IllegalArgumentException("Usage: BenchmarkPolicyMain --input <comparison-runs.json>");
        }
        List<BenchmarkComparisonRun> runs = McpJsonDefaults.getMapper().readValue(Files.readAllBytes(Path.of(arguments[1])), RUNS);
        System.out.write(McpJsonDefaults.getMapper().writeValueAsBytes(BenchmarkPolicy.evaluate(runs)));
        System.out.write('\n');
    }
}
