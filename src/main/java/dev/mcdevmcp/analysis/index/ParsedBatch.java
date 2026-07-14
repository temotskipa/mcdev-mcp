package dev.mcdevmcp.analysis.index;

import java.util.List;

record ParsedBatch(List<ParsedType> types, List<IndexDiagnostic> diagnostics) {
    ParsedBatch {
        types = List.copyOf(types);
        diagnostics = List.copyOf(diagnostics);
    }
}
