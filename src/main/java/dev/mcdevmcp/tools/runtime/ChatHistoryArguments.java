package dev.mcdevmcp.tools.runtime;

record ChatHistoryArguments(Number limit, Boolean includeJson) {
    static ChatHistoryArguments from(ChatHistoryWireArguments wire) {
        return new ChatHistoryArguments(RuntimeToolSupport.optionalNumber(wire.limit(), "limit"), RuntimeToolSupport.optionalBoolean(wire.includeJson(), "includeJson"));
    }
}
