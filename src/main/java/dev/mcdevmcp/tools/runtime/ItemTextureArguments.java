package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;

record ItemTextureArguments(BigDecimal slot) {
    static ItemTextureArguments from(ItemTextureWireArguments wire) {
        return new ItemTextureArguments(RuntimeToolSupport.requiredDecimal(wire.slot(), "slot"));
    }
}
