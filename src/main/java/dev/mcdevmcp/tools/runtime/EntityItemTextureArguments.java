package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;

record EntityItemTextureArguments(BigDecimal entityId, String slot) {
    static EntityItemTextureArguments from(EntityItemTextureWireArguments wire) {
        return new EntityItemTextureArguments(RuntimeToolSupport.requiredDecimal(wire.entityId(), "entityId"), RuntimeToolSupport.requiredString(wire.slot(), "slot"));
    }
}
