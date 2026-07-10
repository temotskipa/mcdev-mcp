package dev.mcdevmcp.app;

import dev.mcdevmcp.support.AppVersion;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

@Command(
        name = "mcdev-mcp",
        mixinStandardHelpOptions = true,
        versionProvider = McdevCommand.VersionProvider.class,
        description = "Minecraft mod-development MCP server")
public final class McdevCommand implements Runnable {
    @Override
    public void run() {
        // Commands are introduced by later migration tasks.
    }

    public static final class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {AppVersion.current()};
        }
    }
}
