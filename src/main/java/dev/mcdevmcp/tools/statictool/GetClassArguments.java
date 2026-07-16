package dev.mcdevmcp.tools.statictool;

record GetClassArguments(TextArgument className, ClassView view, String version) {
    static GetClassArguments from(GetClassWireArguments wire) {
        return new GetClassArguments(TextArgument.fromWire(wire.className()), ClassView.from(TextArgument.fromWire(wire.view())), wire.version());
    }
}
