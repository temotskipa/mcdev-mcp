package dev.mcdevmcp.tools.runtime;

record ItemTextureByIdArguments(String itemId) {
    static ItemTextureByIdArguments from(ItemTextureByIdWireArguments wire) {
        return new ItemTextureByIdArguments(RuntimeToolSupport.requiredString(wire.itemId(), "itemId"));
    }
}
