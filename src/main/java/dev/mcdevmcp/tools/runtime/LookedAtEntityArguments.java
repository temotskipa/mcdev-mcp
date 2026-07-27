package dev.mcdevmcp.tools.runtime;

record LookedAtEntityArguments(Number range) {
    static LookedAtEntityArguments from(LookedAtEntityWireArguments wire) {
        return new LookedAtEntityArguments(RuntimeToolSupport.optionalNumber(wire.range(), "range"));
    }
}