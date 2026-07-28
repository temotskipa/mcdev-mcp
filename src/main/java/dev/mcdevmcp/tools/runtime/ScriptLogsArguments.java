package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;

record ScriptLogsArguments(ScriptLogMode mode, BigDecimal limit) {
    static ScriptLogsArguments from(ScriptLogsWireArguments wire) {
        String mode = SessionControlSupport.optionalString(wire.mode(), "mode");
        BigDecimal limit = RuntimeToolSupport.optionalDecimal(wire.limit(), "limit");
        return new ScriptLogsArguments(ScriptLogMode.fromWire(mode), limit == null || limit.signum() == 0 ? BigDecimal.valueOf(20) : limit);
    }
}
