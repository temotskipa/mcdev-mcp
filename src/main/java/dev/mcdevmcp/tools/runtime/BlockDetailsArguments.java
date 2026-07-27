package dev.mcdevmcp.tools.runtime;

record BlockDetailsArguments(Number x, Number y, Number z) {
    static BlockDetailsArguments from(BlockDetailsWireArguments wire) {
        return new BlockDetailsArguments(RuntimeToolSupport.requiredNumber(wire.x(), "x"), RuntimeToolSupport.requiredNumber(wire.y(), "y"), RuntimeToolSupport.requiredNumber(wire.z(), "z"));
    }
}