package dev.mcdevmcp.app;

import picocli.CommandLine.Command;

@Command(name = "mcdev-mcp", mixinStandardHelpOptions = true, versionProvider = McdevVersionProvider.class, description = "Minecraft mod-development MCP server", subcommands = ServeCommand.class)
public final class McdevCommand implements Runnable {
    @Override
    public void run() {
        // Commands are introduced by later migration tasks.
    }
}
