package dev.mcdevmcp.tools.statictool;

enum VersionAction {
    set, list, unknown;

    static VersionAction from(TextArgument value) {
        if ("set".equals(value.value())) {
            return set;
        }
        return "list".equals(value.value()) ? list : unknown;
    }
}
