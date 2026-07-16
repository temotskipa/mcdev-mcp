package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.binding.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.ToolResult;
import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.stream.Collectors;

final class McVersionTool {
    private McVersionTool() {
    }
    
    static ToolBinding<VersionArguments> binding(StaticToolSupport support) {
        var decoder = ArgumentDecoder.sdk(VersionWireArguments.class).map(VersionArguments::from);
        return ToolBinding.blocking(decoder, (arguments, _) -> support.execute("mc_version", () -> {
            if (arguments.action() == VersionAction.set) {
                return set(support, arguments);
            }
            if (arguments.action() == VersionAction.list) {
                return list(support);
            }
            return ToolResult.error("Unknown action: " + arguments.actionText().display());
        }));
    }
    
    private static ToolResult set(StaticToolSupport support, VersionArguments arguments) {
        if (arguments.version() == null || arguments.version().isBlank()) {
            return ToolResult.error("Error: 'version' is required for set action");
        }
        MinecraftVersion version = new MinecraftVersion(arguments.version());
        if (!Files.isDirectory(support.paths().sourceRoot(version))) {
            return ToolResult.text("Version " + arguments.version() + " not initialized.\n\n" + "STOP and ask the USER to run this command in their terminal:\n" + "  npx mcdev-mcp init -v " + arguments.version() + "\n\n" + "This will download, decompile, and index Minecraft " + arguments.version() + " sources.");
        }
        if (!support.indexed(version)) {
            return ToolResult.text("Version " + arguments.version() + " not indexed.\n\n" + "STOP and ask the USER to run this command in their terminal:\n" + "  npx mcdev-mcp init -v " + arguments.version() + "\n\n" + "This will index Minecraft " + arguments.version() + " sources.");
        }
        support.activate(version);
        String callgraph = Files.isRegularFile(support.paths().callgraphDatabase(version)) ? "yes" : "no";
        return ToolResult.text("Active version set to " + arguments.version() + ".\nIndexed: yes\nCallgraph: " + callgraph);
    }
    
    private static ToolResult list(StaticToolSupport support) {
        var versions = new ArrayList<String>();
        PathWalker.listDirectories(support.paths().cacheRoot().resolve("cache"), versions);
        if (versions.isEmpty()) {
            return ToolResult.text("""
                                   No Minecraft versions found.
                                   
                                   Run this command to initialize a version:
                                     npx mcdev-mcp init -v <version>
                                   
                                   Example:
                                     npx mcdev-mcp init -v 1.21.11
                                   """.stripTrailing());
        }
        versions.sort(String::compareTo);
        String lines = versions.stream().map(value -> {
            MinecraftVersion version = new MinecraftVersion(value);
            String decompiled = PathWalker.isDecompiled(support.paths().sourceRoot(version)) ? "decompiled" : "not decompiled";
            String indexed = support.indexed(version) ? "indexed" : "not indexed";
            String callgraph = Files.isRegularFile(support.paths().callgraphDatabase(version)) ? "callgraph" : "no callgraph";
            return value + ": " + decompiled + ", " + indexed + ", " + callgraph;
        }).collect(Collectors.joining("\n"));
        String active = support.active().map(version -> "\n\nActive version: " + version.value()).orElse("\n\nNo active version set. Use mc_version with action=\"set\".");
        return ToolResult.text("Available Minecraft versions:\n" + lines + active);
    }
}
