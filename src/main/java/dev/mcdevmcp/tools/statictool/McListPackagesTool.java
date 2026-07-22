package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.ToolResult;

final class McListPackagesTool {
    private static final LimitSpec LIMIT = new LimitSpec(500, 5000);

    private McListPackagesTool() {
    }

    static ToolBinding<ListPackagesArguments> binding(StaticToolSupport support) {
        var decoder = ArgumentDecoder.sdk(ListPackagesWireArguments.class).map(ListPackagesArguments::from);
        return ToolBinding.blocking(decoder, (arguments, _) -> support.execute("mc_list_packages", () -> {
            var version = support.resolve(arguments.version());
            var limit = LIMIT.normalize(arguments.limit().value());
            String namespace = arguments.namespace() == null ? null : arguments.namespace().wireName();
            var page = support.repository(version).packages(namespace, limit.value() + 1);
            var rows = page.packages();
            boolean truncated = rows.size() > limit.value();
            if (truncated) {
                rows = rows.subList(0, limit.value());
            }
            if (page.total() == 0) {
                return ToolResult.text("No packages found");
            }
            return ToolResult.text("Found " + page.total() + " package(s):\n" + String.join("\n", rows) + StaticTools.truncationNote(rows.size(), page.total(), truncated, limit, "package(s)"));
        }));
    }
}
