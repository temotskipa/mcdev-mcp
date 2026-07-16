package dev.mcdevmcp.tools.statictool;

record ListPackagesArguments(SourceNamespaceArgument namespace, LimitInput limit, String version) {
    static ListPackagesArguments from(ListPackagesWireArguments wire) {
        return new ListPackagesArguments(SourceNamespaceArgument.from(TextArgument.fromWire(wire.namespace())), LimitInput.fromWire(wire.limit()), wire.version());
    }
}
