package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;

record EntityGlowArguments(BigDecimal entityId, boolean glow) {
    static EntityGlowArguments from(EntityGlowWireArguments wire) {
        return new EntityGlowArguments(RuntimeToolSupport.requiredDecimal(wire.entityId(), "entityId"), RuntimeToolSupport.requiredGlow(wire.glow()));
    }
}
