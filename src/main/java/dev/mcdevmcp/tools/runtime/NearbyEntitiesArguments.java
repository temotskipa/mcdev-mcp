package dev.mcdevmcp.tools.runtime;

record NearbyEntitiesArguments(Number range, Number limit, Boolean includeIcons) {
    static NearbyEntitiesArguments from(NearbyEntitiesWireArguments wire) {
        return new NearbyEntitiesArguments(RuntimeToolSupport.optionalNumber(wire.range(), "range"), RuntimeToolSupport.optionalNumber(wire.limit(), "limit"), RuntimeToolSupport.optionalBoolean(wire.includeIcons(), "includeIcons"));
    }
}