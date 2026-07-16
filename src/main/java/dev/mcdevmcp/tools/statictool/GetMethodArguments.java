package dev.mcdevmcp.tools.statictool;

record GetMethodArguments(TextArgument className, TextArgument methodName, String version) {
    static GetMethodArguments from(GetMethodWireArguments wire) {
        return new GetMethodArguments(TextArgument.fromWire(wire.className()), TextArgument.fromWire(wire.methodName()), wire.version());
    }
}
