package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.model.ClassSymbol;
import dev.mcdevmcp.storage.model.ElementKindCodec;
import dev.mcdevmcp.storage.model.SearchHit;
import dev.mcdevmcp.storage.model.SearchHitKind;

import java.util.stream.Collectors;

final class McSearchTool {
    private static final LimitSpec LIMIT = new LimitSpec(50, 1000);

    private McSearchTool() {
    }

    static ToolBinding<SearchArguments> binding(StaticToolSupport support) {
        var decoder = ArgumentDecoder.sdk(SearchWireArguments.class).map(SearchArguments::from);
        return ToolBinding.blockingCompatibility(decoder, (arguments, _) -> support.execute("mc_search", () -> {
            if (arguments.query().isMissing()) {
                return ToolResult.error("Error executing mc_search: Cannot read properties of undefined (reading 'toLowerCase')");
            }
            if (!arguments.query().isText()) {
                return ToolResult.error("Error executing mc_search: query.toLowerCase is not a function");
            }
            String query = arguments.query().value();
            var version = support.resolve(arguments.version());
            var limit = LIMIT.normalize(arguments.limit().value());
            int effectiveLimit = limit.value() == 0 ? LIMIT.defaultValue() : limit.value();
            String type = arguments.type() == null ? null : arguments.typeText().value();
            var rows = support.repository(version).search(query, type, effectiveLimit + 1);
            boolean truncated = rows.size() >= effectiveLimit;
            if (truncated) {
                rows = rows.subList(0, effectiveLimit);
            }
            if (rows.isEmpty()) {
                String suffix = arguments.type() == null ? "" : " (type: " + arguments.typeText().display() + ")";
                return ToolResult.text("No results found for \"" + query + "\"" + suffix);
            }
            String renderedRows = rows.stream().map(McSearchTool::render).collect(Collectors.joining("\n"));
            return ToolResult.text("Found " + rows.size() + " result(s):\n" + renderedRows + StaticTools.truncationNote(rows.size(), truncated, limit, "result(s)"));
        }));
    }

    private static String render(SearchHit hit) {
        ClassSymbol owner = hit.owner();
        if (hit.kind() == SearchHitKind.CLASS) {
            return renderClass(hit, owner);
        }
        if (hit.kind() == SearchHitKind.FIELD) {
            var field = hit.field().orElseThrow();
            return "[field] " + owner.binaryName() + "#" + field.name() + ": " + StaticToolSupport.modifiers(field.modifiers()) + field.type() + " " + field.name();
        }
        var method = hit.method().orElseThrow();
        String parameters = hit.parameters().stream().map(parameter -> parameter.type() + " " + parameter.name()).collect(Collectors.joining(", "));
        return "[method] " + owner.binaryName() + "#" + method.name() + ": " + StaticToolSupport.modifiers(method.modifiers()) + StaticToolSupport.returnType(method.returnType().orElse(null)) + " " + method.name() + "(" + parameters + ") (line " + method.startLine() + ")";
    }

    private static String renderClass(SearchHit hit, ClassSymbol owner) {
        String superclass = owner.superclassBinaryName().map(value -> " extends " + value).orElse("");
        String interfaces = "";
        if (!owner.interfaceBinaryNames().isEmpty()) {
            interfaces = " implements " + String.join(", ", owner.interfaceBinaryNames().stream().limit(3).toList());
            if (owner.interfaceBinaryNames().size() > 3) {
                interfaces += " (+" + (owner.interfaceBinaryNames().size() - 3) + ")";
            }
        }
        return "[" + ElementKindCodec.wireName(owner.kind()) + "] " + owner.binaryName() + superclass + interfaces + " (" + hit.fieldCount() + " fields, " + hit.methodCount() + " methods)";
    }
}
