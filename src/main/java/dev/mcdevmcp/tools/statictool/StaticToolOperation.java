package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.api.ToolResult;

import java.io.IOException;
import java.sql.SQLException;

@FunctionalInterface
interface StaticToolOperation {
    ToolResult run() throws IOException, SQLException;
}
