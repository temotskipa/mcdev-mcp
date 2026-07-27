package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;

record NearbyEntitiesArguments(BigDecimal range, BigDecimal limit, Boolean includeIcons) {
    static NearbyEntitiesArguments from(NearbyEntitiesWireArguments wire) {
        return new NearbyEntitiesArguments(RuntimeToolSupport.optionalDecimal(wire.range(), "range"), RuntimeToolSupport.optionalDecimal(wire.limit(), "limit"), RuntimeToolSupport.optionalBoolean(wire.includeIcons(), "includeIcons"));
    }
}