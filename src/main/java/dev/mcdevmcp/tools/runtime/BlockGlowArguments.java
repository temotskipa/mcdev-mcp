package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;

record BlockGlowArguments(BigDecimal x, BigDecimal y, BigDecimal z, boolean glow) {
    static BlockGlowArguments from(BlockGlowWireArguments wire) {
        return new BlockGlowArguments(RuntimeToolSupport.requiredDecimal(wire.x(), "x"), RuntimeToolSupport.requiredDecimal(wire.y(), "y"), RuntimeToolSupport.requiredDecimal(wire.z(), "z"), RuntimeToolSupport.requiredGlow(wire.glow()));
    }
}
