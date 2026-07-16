package dev.mcdevmcp.tools.statictool;

record VersionArguments(VersionAction action, TextArgument actionText, String version) {
    static VersionArguments from(VersionWireArguments wire) {
        TextArgument actionText = TextArgument.fromWire(wire.action());
        return new VersionArguments(VersionAction.from(actionText), actionText, wire.version());
    }
}
