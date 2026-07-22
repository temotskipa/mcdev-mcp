package dev.mcdevmcp.app;

import dev.mcdevmcp.storage.CacheCleaner;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.callgraph.CallgraphCleaner;
import dev.mcdevmcp.storage.h2.IndexCleaner;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

@Command(name = "clean", description = "Clean cached analysis artifacts")
@SuppressWarnings("unused")
public final class CleanCommand implements Callable<Integer> {
    private final PlatformPaths paths;

    @Option(names = {"-v", "--version"}, description = "Minecraft version")
    private String version;

    @Option(names = "--index", description = "Clean the H2 index")
    private boolean index;

    @Option(names = "--cache", description = "Clean the version cache")
    private boolean cache;

    @Option(names = "--callgraph", description = "Clean the JSONL callgraph")
    private boolean callgraph;

    @Option(names = "--all", description = "Clean all supported cached state")
    private boolean all;

    @Spec
    private picocli.CommandLine.Model.CommandSpec spec;

    public CleanCommand(PlatformPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    @Override
    public Integer call() throws IOException {
        int selectors = (index ? 1 : 0) + (cache ? 1 : 0) + (callgraph ? 1 : 0) + (all ? 1 : 0);
        if (selectors != 1) {
            throw new IllegalArgumentException("Specify exactly one of --callgraph, --cache, --index, or --all");
        }

        CacheCleaner cacheCleaner = new CacheCleaner(paths);
        List<MinecraftVersion> versions = version == null ? cacheCleaner.cachedVersions().stream().filter(candidate -> MinecraftVersionValidator.isSupported(candidate.value())).toList() : List.of(new MinecraftVersion(MinecraftVersionValidator.requireSupported(version)));
        if (versions.isEmpty()) {
            spec.commandLine().getOut().println("No cached versions.");
            return 0;
        }

        IndexCleaner indexCleaner = new IndexCleaner(paths);
        CallgraphCleaner callgraphCleaner = new CallgraphCleaner();
        for (MinecraftVersion minecraft : versions) {
            if (index) {
                indexCleaner.cleanIndex(minecraft);
            }
            else if (callgraph) {
                callgraphCleaner.clean(paths.callgraphBundle(minecraft));
            }
            else if (cache) {
                cacheCleaner.cleanCache(minecraft);
            }
            else {
                cacheCleaner.cleanAll(minecraft);
            }
            spec.commandLine().getOut().printf("Cleaned %s for Minecraft %s.%n", selectedArtifact(), minecraft.value());
        }
        return 0;
    }

    private String selectedArtifact() {
        if (index) {
            return "index";
        }
        if (callgraph) {
            return "callgraph";
        }
        if (cache) {
            return "cache";
        }
        return "all cached state";
    }
}
