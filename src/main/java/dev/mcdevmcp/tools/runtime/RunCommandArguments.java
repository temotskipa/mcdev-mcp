package dev.mcdevmcp.tools.runtime;

record RunCommandArguments(String command) {
    static RunCommandArguments from(RunCommandWireArguments wire) {
        return new RunCommandArguments(RuntimeToolSupport.requiredString(wire.command(), "command"));
    }
}
