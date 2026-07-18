package dev.mcdevmcp.tools.statictool;

record FindRefsArguments(TextArgument className, TextArgument methodName, ReferenceDirection direction, TextArgument directionText, LimitInput limit, String version) {
    static FindRefsArguments from(FindRefsWireArguments wire) {
        TextArgument direction = TextArgument.fromWire(wire.direction());
        return new FindRefsArguments(TextArgument.fromWire(wire.className()), TextArgument.fromWire(wire.methodName()), ReferenceDirection.from(direction), direction, LimitInput.fromWire(wire.limit()), wire.version());
    }
}
