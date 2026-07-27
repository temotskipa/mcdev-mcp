package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;

record BlockDetailsArguments(BigDecimal x, BigDecimal y, BigDecimal z) {
    static BlockDetailsArguments from(BlockDetailsWireArguments wire) {
        return new BlockDetailsArguments(RuntimeToolSupport.requiredDecimal(wire.x(), "x"), RuntimeToolSupport.requiredDecimal(wire.y(), "y"), RuntimeToolSupport.requiredDecimal(wire.z(), "z"));
    }
}