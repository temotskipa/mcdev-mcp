package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.ToolResult;
import dev.mcdevmcp.storage.model.ClassSymbol;

import java.util.stream.Collectors;

final class McListClassesTool {
    private static final LimitSpec LIMIT = new LimitSpec(200, 5000);
    
    private McListClassesTool() {
    }
    
    static ToolBinding<ListClassesArguments> binding(StaticToolSupport support) {
        var decoder = ArgumentDecoder.sdk(ListClassesWireArguments.class).map(ListClassesArguments::from);
        return ToolBinding.blocking(decoder, (arguments, _) -> support.execute("mc_list_classes", () -> {
            if (arguments.packagePath().isMissing()) {
                return ToolResult.error("Error executing mc_list_classes: Cannot read properties of undefined (reading 'toLowerCase')");
            }
            if (!arguments.packagePath().isText()) {
                return ToolResult.error("Error executing mc_list_classes: packagePath.toLowerCase is not a function");
            }
            var version = support.resolve(arguments.version());
            var limit = LIMIT.normalize(arguments.limit().value());
            int queryLimit = limit.value() == 0 ? Integer.MAX_VALUE : limit.value() + 1;
            var rows = support.repository(version).classesUnder(arguments.packagePath().value(), queryLimit);
            boolean truncated = limit.value() > 0 && rows.size() >= limit.value();
            if (truncated) {
                rows = rows.subList(0, limit.value());
            }
            if (rows.isEmpty()) {
                return ToolResult.text("No classes found under package \"" + arguments.packagePath().display() + "\"");
            }
            String renderedRows = rows.stream().map(ClassSymbol::binaryName).collect(Collectors.joining("\n"));
            return ToolResult.text("Classes under \"" + arguments.packagePath().display() + "\":\n" + renderedRows + StaticTools.truncationNote(rows.size(), truncated, limit, "class(es)"));
        }));
    }
}
