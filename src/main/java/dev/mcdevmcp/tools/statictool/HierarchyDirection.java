package dev.mcdevmcp.tools.statictool;

enum HierarchyDirection {
    subclasses, implementors, UNKNOWN;
    
    static HierarchyDirection from(TextArgument value) {
        if ("subclasses".equals(value.value())) return subclasses;
        if ("implementors".equals(value.value())) return implementors;
        return UNKNOWN;
    }
}
