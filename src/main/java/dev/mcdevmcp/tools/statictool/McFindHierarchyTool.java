package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.ToolResult;
import dev.mcdevmcp.storage.model.ClassSymbol;

import java.util.stream.Collectors;

final class McFindHierarchyTool {
    private static final LimitSpec LIMIT = new LimitSpec(200, 5000);

    private McFindHierarchyTool() {
    }

    static ToolBinding<FindHierarchyArguments> binding(StaticToolSupport support) {
        var decoder = ArgumentDecoder.sdk(FindHierarchyWireArguments.class).map(FindHierarchyArguments::from);
        return ToolBinding.blocking(decoder, (arguments, _) -> support.execute("mc_find_hierarchy", () -> {
            var version = support.resolve(arguments.version());
            var limit = LIMIT.normalize(arguments.limit().value());
            if (arguments.direction() == HierarchyDirection.UNKNOWN) {
                return ToolResult.text("No " + arguments.directionText().display() + " found for " + arguments.className().display());
            }
            int queryLimit = limit.value() == 0 ? Integer.MAX_VALUE : limit.value() + 1;
            String classNameValue = arguments.className().isText() ? arguments.className().value() : null;
            var rows = support.repository(version).hierarchy(classNameValue, arguments.direction() == HierarchyDirection.subclasses, queryLimit);
            boolean truncated = limit.value() > 0 && rows.size() >= limit.value();
            if (truncated) {
                rows = rows.subList(0, limit.value());
            }
            String direction = arguments.directionText().display();
            String className = arguments.className().display();
            if (rows.isEmpty()) {
                return ToolResult.text("No " + direction + " found for " + className);
            }
            String heading = arguments.direction() == HierarchyDirection.subclasses ? "Subclasses" : "Implementors";
            String renderedRows = rows.stream().map(ClassSymbol::binaryName).collect(Collectors.joining("\n"));
            return ToolResult.text(heading + " of " + className + ":\n" + renderedRows + StaticTools.truncationNote(rows.size(), truncated, limit, direction));
        }));
    }
}
