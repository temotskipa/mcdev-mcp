package dev.mcdevmcp.tools.statictool;

enum ReferenceDirection {
    callers, callees;

    static ReferenceDirection from(TextArgument value) {
        return value.isText() && value.value().equals("callers") ? callers : callees;
    }
}
