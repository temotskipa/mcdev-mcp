package dev.mcdevmcp.app;

import dev.mcdevmcp.mcp.McpServerFactory;
import dev.mcdevmcp.support.AppEnvironment;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "serve", description = "Start the MCP server over stdio")
public final class ServeCommand implements Callable<Integer> {
    @Override
    public Integer call() throws Exception {
        try (var server = new McpServerFactory(AppEnvironment.system()).startStdio(System.in, System.out)) {
            server.awaitInputClosed();
            return 0;
        }
    }
}
