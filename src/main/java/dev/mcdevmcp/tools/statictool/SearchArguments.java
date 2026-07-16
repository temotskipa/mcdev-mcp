package dev.mcdevmcp.tools.statictool;

record SearchArguments(TextArgument query, SearchType type, TextArgument typeText, LimitInput limit, String version) {
    static SearchArguments from(SearchWireArguments wire) {
        TextArgument typeText = TextArgument.fromWire(wire.type());
        return new SearchArguments(TextArgument.fromWire(wire.query()), SearchType.from(typeText), typeText, LimitInput.fromWire(wire.limit()), wire.version());
    }
}
