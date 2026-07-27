package dev.mcdevmcp.tools.runtime;

record ExecuteArguments(String code, int timeoutMillis) {
    static ExecuteArguments from(ExecuteWireArguments wire) {
        return new ExecuteArguments(RuntimeToolSupport.requiredCode(wire.code()), RuntimeToolSupport.timeoutMillis(wire.timeoutMs()));
    }
}