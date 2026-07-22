package dev.mcdevmcp.app;

import dev.mcdevmcp.storage.CacheCleaner;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.callgraph.CallgraphRepository;
import dev.mcdevmcp.storage.h2.VersionStateRepository;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;

@Command(name = "status", description = "Show cached analysis state")
@SuppressWarnings("unused")
public final class StatusCommand implements Callable<Integer> {
    private final PlatformPaths paths;

    @Option(names = {"-v", "--version"}, description = "Minecraft version")
    private String version;

    @Spec
    private picocli.CommandLine.Model.CommandSpec spec;

    public StatusCommand(PlatformPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    @Override
    public Integer call() throws IOException {
        VersionStateRepository states = new VersionStateRepository(paths);
        if (version != null) {
            print(states, new MinecraftVersion(MinecraftVersionValidator.requireSupported(version)));
            return 0;
        }

        List<MinecraftVersion> versions = new CacheCleaner(paths).cachedVersions().stream().filter(candidate -> MinecraftVersionValidator.isSupported(candidate.value())).toList();
        if (versions.isEmpty()) {
            spec.commandLine().getOut().println("No cached versions.");
            return 0;
        }
        versions.forEach(candidate -> print(states, candidate));
        return 0;
    }

    private void print(VersionStateRepository states, MinecraftVersion value) {
        String state = states.state(value).name().toLowerCase(Locale.ROOT).replace('_', '-');
        String graph = switch (CallgraphRepository.publicationStatus(paths.callgraphBundle(value))) {
            case ABSENT -> "absent";
            case PUBLISHED -> "present";
            case CORRUPT -> "corrupt";
        };
        spec.commandLine().getOut().printf("%s: %s, callgraph %s%n", value.value(), state, graph);
    }
}
