package dev.mcdevmcp.tools.statictool;

enum SourceNamespaceArgument {
    minecraft, fabric;
    
    static SourceNamespaceArgument from(TextArgument value) {
        if (value.isMissing()) return null;
        return "minecraft".equals(value.value()) ? minecraft : fabric;
    }
    
    String wireName() {
        return name();
    }
}
