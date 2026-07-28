package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;

record JoinServerArguments(String address, boolean acceptResourcePacks, boolean waitForWorld, BigDecimal timeoutSeconds) {
    static JoinServerArguments from(JoinServerWireArguments wire) {
        Boolean acceptResourcePacks = RuntimeToolSupport.optionalBoolean(wire.acceptResourcePacks, "acceptResourcePacks");
        Boolean wait = RuntimeToolSupport.optionalBoolean(wire.wait, "wait");
        return new JoinServerArguments(RuntimeToolSupport.requiredString(wire.address, "address"), acceptResourcePacks == null || acceptResourcePacks, wait == null || wait, SessionControlSupport.timeoutSeconds(wire.timeoutSeconds, SessionControlSupport.DEFAULT_JOIN_TIMEOUT_SECONDS));
    }
}
