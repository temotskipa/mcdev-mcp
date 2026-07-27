package dev.mcdevmcp.tools.runtime;

record EntityDetailsArguments(Number entityId) {
    static EntityDetailsArguments from(EntityDetailsWireArguments wire) {
        return new EntityDetailsArguments(RuntimeToolSupport.requiredNumber(wire.entityId(), "entityId"));
    }
}