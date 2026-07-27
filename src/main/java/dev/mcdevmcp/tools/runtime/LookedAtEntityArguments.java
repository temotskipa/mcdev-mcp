package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;

record LookedAtEntityArguments(BigDecimal range) {
    static LookedAtEntityArguments from(LookedAtEntityWireArguments wire) {
        return new LookedAtEntityArguments(RuntimeToolSupport.optionalDecimal(wire.range(), "range"));
    }
}