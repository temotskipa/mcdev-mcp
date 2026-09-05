package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.minecraft.BlockPosition;
import dev.mcdevmcp.mcp.tool.api.InputProperty;

import java.util.Objects;

value record BlockDetailsArguments(@InputProperty(required = true) BlockPosition position) {
    BlockDetailsArguments {
        Objects.requireNonNull(position, "position");
    }
}
