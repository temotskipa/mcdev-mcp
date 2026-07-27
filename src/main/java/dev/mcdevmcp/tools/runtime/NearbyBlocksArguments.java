package dev.mcdevmcp.tools.runtime;

record NearbyBlocksArguments(Number range, Number limit) {
    static NearbyBlocksArguments from(NearbyBlocksWireArguments wire) {
        return new NearbyBlocksArguments(RuntimeToolSupport.optionalNumber(wire.range(), "range"), RuntimeToolSupport.optionalNumber(wire.limit(), "limit"));
    }
}