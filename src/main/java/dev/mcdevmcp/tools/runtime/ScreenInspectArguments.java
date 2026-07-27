package dev.mcdevmcp.tools.runtime;

record ScreenInspectArguments(Boolean includeIcons) {
    static ScreenInspectArguments from(ScreenInspectWireArguments wire) {
        return new ScreenInspectArguments(RuntimeToolSupport.optionalBoolean(wire.includeIcons(), "includeIcons"));
    }
}
