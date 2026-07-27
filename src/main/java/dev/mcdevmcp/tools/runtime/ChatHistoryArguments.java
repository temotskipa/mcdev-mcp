package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;

record ChatHistoryArguments(BigDecimal limit, Boolean includeJson) {
    static ChatHistoryArguments from(ChatHistoryWireArguments wire) {
        return new ChatHistoryArguments(RuntimeToolSupport.optionalDecimal(wire.limit(), "limit"), RuntimeToolSupport.optionalBoolean(wire.includeJson(), "includeJson"));
    }
}
