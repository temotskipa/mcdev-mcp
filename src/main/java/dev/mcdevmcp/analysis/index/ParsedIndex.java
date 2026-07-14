package dev.mcdevmcp.analysis.index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

record ParsedIndex(List<ParsedType> types, List<IndexDiagnostic> diagnostics) {
    ParsedIndex {
        List<ParsedType> sortedTypes = new ArrayList<>(types);
        sortedTypes.sort(Comparator.comparing(ParsedType::sourceRoot).thenComparing(type -> portable(type.sourcePath())).thenComparingInt(type -> type.range().startOffset()).thenComparing(ParsedType::binaryName));
        types = List.copyOf(sortedTypes);
        List<IndexDiagnostic> sortedDiagnostics = new ArrayList<>(diagnostics);
        sortedDiagnostics.sort(IndexDiagnostic.ORDERING);
        diagnostics = List.copyOf(sortedDiagnostics);
    }

    private static String portable(java.nio.file.Path path) {
        StringBuilder value = new StringBuilder();
        for (java.nio.file.Path part : path) {
            if (!value.isEmpty()) {
                value.append('/');
            }
            value.append(part);
        }
        return value.toString();
    }
}
