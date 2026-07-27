package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;

record EntityDetailsArguments(BigDecimal entityId) {
    static EntityDetailsArguments from(EntityDetailsWireArguments wire) {
        return new EntityDetailsArguments(RuntimeToolSupport.requiredDecimal(wire.entityId(), "entityId"));
    }
}