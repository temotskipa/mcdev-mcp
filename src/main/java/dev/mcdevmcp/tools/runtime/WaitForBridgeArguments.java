package dev.mcdevmcp.tools.runtime;

import java.math.BigDecimal;
import java.util.Optional;

import dev.mcdevmcp.storage.model.MinecraftVersion;

record WaitForBridgeArguments(Optional<MinecraftVersion> expectedVersion, BigDecimal timeoutSeconds) {
    static WaitForBridgeArguments from(WaitForBridgeWireArguments wire) {
        String expectedVersion = SessionControlSupport.optionalString(wire.expectedVersion(), "expectedVersion");
        return new WaitForBridgeArguments(Optional.ofNullable(expectedVersion).filter(value -> !value.isEmpty()).map(MinecraftVersion::new), SessionControlSupport.timeoutSeconds(wire.timeoutSeconds(), SessionControlSupport.DEFAULT_BRIDGE_WAIT_TIMEOUT_SECONDS));
    }
}
