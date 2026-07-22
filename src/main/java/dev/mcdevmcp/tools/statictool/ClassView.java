package dev.mcdevmcp.tools.statictool;

enum ClassView {
    summary, methods, fields, full;

    static ClassView from(TextArgument value) {
        if ("methods".equals(value.value())) return methods;
        if ("fields".equals(value.value())) return fields;
        if ("full".equals(value.value())) return full;
        return summary;
    }
}
