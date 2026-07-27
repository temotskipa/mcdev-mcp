package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;

record NearbyBlocksArguments(BigDecimal range, BigDecimal limit) {
    static NearbyBlocksArguments from(NearbyBlocksWireArguments wire) {
        return new NearbyBlocksArguments(RuntimeToolSupport.optionalDecimal(wire.range(), "range"), RuntimeToolSupport.optionalDecimal(wire.limit(), "limit"));
    }
}