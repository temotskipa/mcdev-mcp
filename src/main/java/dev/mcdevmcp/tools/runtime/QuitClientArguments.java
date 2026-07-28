package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;

record QuitClientArguments(boolean waitForExit, BigDecimal timeoutSeconds) {
    static QuitClientArguments from(QuitClientWireArguments wire) {
        Boolean waitForExit = RuntimeToolSupport.optionalBoolean(wire.waitForExit(), "waitForExit");
        return new QuitClientArguments(waitForExit == null || waitForExit, SessionControlSupport.timeoutSeconds(wire.timeoutSeconds(), SessionControlSupport.DEFAULT_QUIT_TIMEOUT_SECONDS));
    }
}
