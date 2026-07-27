package dev.mcdevmcp.tools.runtime;

record ConnectArguments(Integer port, boolean reset) {
    static ConnectArguments from(ConnectWireArguments wire) {
        return new ConnectArguments(RuntimeToolSupport.optionalPort(wire.port()), Boolean.TRUE.equals(RuntimeToolSupport.optionalBoolean(wire.reset(), "reset")));
    }
}