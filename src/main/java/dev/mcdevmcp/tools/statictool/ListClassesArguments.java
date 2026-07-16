package dev.mcdevmcp.tools.statictool;

record ListClassesArguments(TextArgument packagePath, LimitInput limit, String version) {
    static ListClassesArguments from(ListClassesWireArguments wire) {
        return new ListClassesArguments(TextArgument.fromWire(wire.packagePath()), LimitInput.fromWire(wire.limit()), wire.version());
    }
}
