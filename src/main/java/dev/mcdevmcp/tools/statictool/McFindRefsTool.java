package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ArgumentDecoder;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.callgraph.CallgraphRepository;
import dev.mcdevmcp.storage.model.MethodReference;
import dev.mcdevmcp.support.AppVersion;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

final class McFindRefsTool {
    static final LimitSpec LIMIT = new LimitSpec(100, 5000);
    private static final int MAX_CAUSE_LENGTH = 500;

    private McFindRefsTool() {
    }

    static ToolBinding<FindRefsArguments> binding(StaticToolSupport support) {
        var decoder = ArgumentDecoder.sdk(FindRefsWireArguments.class).map(FindRefsArguments::from);
        return ToolBinding.blocking(decoder, (arguments, _) -> support.execute("mc_find_refs", () -> {
            var version = support.resolve(arguments.version());
            String direction = arguments.directionText().display();
            String className = arguments.className().display();
            String methodName = arguments.methodName().display();
            CallgraphRepository.PublicationStatus publicationStatus = CallgraphRepository.publicationStatus(support.paths().callgraphBundle(version));
            if (publicationStatus == CallgraphRepository.PublicationStatus.CORRUPT) {
                return ToolResult.text("Version " + version.value() + " has corrupt callgraph data.\n\n" + "STOP and ask the USER to run this command in their terminal:\n" + "  java -jar " + AppVersion.executableJarName() + " callgraph -v " + version.value() + "\n\n" + "Or for full reinitialization:\n  java -jar " + AppVersion.executableJarName() + " init -v " + version.value());
            }
            if (publicationStatus == CallgraphRepository.PublicationStatus.ABSENT) {
                return ToolResult.text("Version " + version.value() + " does not have callgraph data.\n\n" + "STOP and ask the USER to run this command in their terminal:\n" + "  java -jar " + AppVersion.executableJarName() + " callgraph -v " + version.value() + "\n\n" + "Or for full reinitialization:\n  java -jar " + AppVersion.executableJarName() + " init -v " + version.value());
            }
            var limit = LIMIT.normalize(arguments.limit().value());
            int queryLimit = limit.value() + 1;
            List<MethodReference> fetched;
            try {
                if (arguments.className().isMissing() || arguments.methodName().isMissing()) {
                    throw new IOException("Wrong API use : tried to bind a value of an unknown type (undefined).");
                }
                fetched = arguments.direction() == ReferenceDirection.callers ? support.callgraphRepository(version).callers(arguments.className().value(), arguments.methodName().value(), queryLimit) : support.callgraphRepository(version).callees(arguments.className().value(), arguments.methodName().value(), queryLimit);
            } catch (IOException | RuntimeException exception) {
                return ToolResult.error("Failed to query callgraph for " + className + "#" + methodName + " (" + direction + "): " + boundedCause(exception));
            }
            if (fetched.isEmpty()) {
                return ToolResult.text("No " + direction + " found for " + className + "#" + methodName);
            }
            int fetchedCount = fetched.size();
            boolean truncated = fetchedCount > limit.value();
            List<MethodReference> shown = truncated ? fetched.subList(0, limit.value()) : fetched;
            String rows = shown.stream().map(McFindRefsTool::render).collect(Collectors.joining("\n"));
            return ToolResult.text("Found " + fetchedCount + " " + direction + ":\n" + rows + StaticTools.truncationNote(shown.size(), fetchedCount, truncated, limit, direction));
        }));
    }

    private static String render(MethodReference reference) {
        Integer line = reference.lineNumber();
        return reference.displayName() + (line == null || line == 0 ? "" : " (line " + line + ")");
    }

    private static String boundedCause(Exception exception) {
        String message = exception.getMessage();
        String value = message == null ? exception.toString() : message;
        value = value.replace('\r', ' ').replace('\n', ' ').trim();
        return value.length() <= MAX_CAUSE_LENGTH ? value : value.substring(0, MAX_CAUSE_LENGTH) + "...";
    }
}
