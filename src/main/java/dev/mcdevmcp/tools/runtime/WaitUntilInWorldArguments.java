package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;

record WaitUntilInWorldArguments(BigDecimal timeoutSeconds, boolean requireAbsenceFirst) {
    static WaitUntilInWorldArguments from(WaitUntilInWorldWireArguments wire) {
        return new WaitUntilInWorldArguments(SessionControlSupport.timeoutSeconds(wire.timeoutSeconds(), SessionControlSupport.DEFAULT_JOIN_TIMEOUT_SECONDS), Boolean.TRUE.equals(RuntimeToolSupport.optionalBoolean(wire.requireAbsenceFirst(), "requireAbsenceFirst")));
    }
}
