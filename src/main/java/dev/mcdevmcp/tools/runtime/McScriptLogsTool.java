package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.ToolHandlers;
import dev.mcdevmcp.mcp.tool.ToolResult;

import java.util.List;

final class McScriptLogsTool {
    private McScriptLogsTool() {
    }

    static ToolBinding<ScriptLogsArguments> binding(ScriptLogger logger) {
        var decoder = ArgumentDecoder.sdk(ScriptLogsWireArguments.class).map(ScriptLogsArguments::from);
        return new ToolBinding<>(decoder, (arguments, _) -> ToolHandlers.completed(render(logger, arguments)));
    }

    private static ToolResult render(ScriptLogger logger, ScriptLogsArguments arguments) {
        if (logger == null) {
            return ToolResult.text("Session logging is disabled. Set the MCDEV_SESSION_LOG_DIR environment variable to enable it.");
        }
        return switch (arguments.mode()) {
            case PATHS ->
                    ToolResult.text("Script log files:\n" + "  All executions: " + logger.allLogPath() + "\n" + "  Errors only:    " + logger.errorsLogPath() + "\n" + "  Log directory:  " + logger.logDirectory() + "\n\n" + "Use the Read tool to view these files. Format: JSON Lines (one JSON object per line).");
            case STATS -> renderStats(logger.errorStats());
            case ERRORS -> renderErrors(logger.recentErrors(limit(arguments)));
        };
    }

    private static int limit(ScriptLogsArguments arguments) {
        long value = arguments.limit().longValue();
        if (value <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    private static ToolResult renderStats(List<ScriptLogger.ScriptErrorStat> stats) {
        if (stats.isEmpty()) {
            return ToolResult.text("No errors logged yet.");
        }
        StringBuilder text = new StringBuilder("Error Statistics (").append(stats.size()).append(" distinct error types):\n\n");
        for (ScriptLogger.ScriptErrorStat stat : stats.subList(0, Math.min(15, stats.size()))) {
            text.append("## ").append(stat.error()).append('\n');
            text.append("   Count: ").append(stat.count()).append(" | Last seen: ").append(stat.lastSeen()).append('\n');
            text.append("   Example script:\n");
            text.append("   ```groovy\n   ").append(stat.examples().getFirst().replace("\n", "\n   ")).append("\n   ```\n\n");
        }
        if (stats.size() > 15) {
            text.append("... and ").append(stats.size() - 15).append(" more error types\n");
        }
        return ToolResult.text(text.toString());
    }

    private static ToolResult renderErrors(List<ScriptLogger.ScriptLogEntry> errors) {
        if (errors.isEmpty()) {
            return ToolResult.text("No errors logged yet.");
        }
        StringBuilder text = new StringBuilder("Recent Script Errors (").append(errors.size()).append(" entries):\n\n");
        for (int index = errors.size() - 1; index >= 0; index--) {
            ScriptLogger.ScriptLogEntry entry = errors.get(index);
            text.append("---\n");
            text.append("**").append(entry.timestamp()).append("** (").append(entry.duration().toMillis()).append("ms)\n");
            text.append("Error: ").append(entry.error()).append('\n');
            text.append("```groovy\n").append(entry.code()).append("\n```\n\n");
        }
        return ToolResult.text(text.toString());
    }
}
