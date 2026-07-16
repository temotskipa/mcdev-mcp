package dev.mcdevmcp.tools.statictool;

record FindHierarchyArguments(TextArgument className, HierarchyDirection direction, TextArgument directionText, LimitInput limit, String version) {
    static FindHierarchyArguments from(FindHierarchyWireArguments wire) {
        TextArgument directionText = TextArgument.fromWire(wire.direction());
        return new FindHierarchyArguments(TextArgument.fromWire(wire.className()), HierarchyDirection.from(directionText), directionText, LimitInput.fromWire(wire.limit()), wire.version());
    }
}
