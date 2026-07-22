package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.storage.PlatformPaths;

import java.util.Map;

public final class StaticToolModule {
    private StaticToolModule() {
    }

    public static Map<String, ToolBinding<?>> handlers(PlatformPaths paths) {
        var support = new StaticToolSupport(paths);
        return Map.ofEntries(Map.entry("mc_version", McVersionTool.binding(support)), Map.entry("mc_search", McSearchTool.binding(support)), Map.entry("mc_get_class", McGetClassTool.binding(support)), Map.entry("mc_get_method", McGetMethodTool.binding(support)), Map.entry("mc_list_classes", McListClassesTool.binding(support)), Map.entry("mc_list_packages", McListPackagesTool.binding(support)), Map.entry("mc_find_hierarchy", McFindHierarchyTool.binding(support)), Map.entry("mc_find_refs", McFindRefsTool.binding(support)));
    }
}
