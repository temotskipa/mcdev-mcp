package dev.mcdevmcp.app;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.Cancellation;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.Objects;
import java.util.concurrent.Callable;

@Command(name = "callgraph", description = "Rebuild the JSONL callgraph from prepared sources")
@SuppressWarnings("unused")
public final class CallgraphCommand implements Callable<Integer> {
    private final AnalysisOperations operations;

    @Option(names = {"-v", "--version"}, required = true, description = "Minecraft version")
    private String version;

    @Spec
    private picocli.CommandLine.Model.CommandSpec spec;

    public CallgraphCommand(AnalysisOperations operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @Override
    public Integer call() {
        MinecraftVersion minecraft = new MinecraftVersion(MinecraftVersionValidator.requireSupported(version));
        var summary = operations.rebuildCallgraph(minecraft, CliProgressSink.forWriter(spec.commandLine().getOut()), Cancellation.none());
        spec.commandLine().getOut().printf("Recorded %d call edges.%n", summary.edges());
        return 0;
    }
}
