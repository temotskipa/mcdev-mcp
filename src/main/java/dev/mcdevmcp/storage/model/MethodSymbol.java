package dev.mcdevmcp.storage.model;

import java.util.Optional;

@SuppressWarnings("unused")
public record MethodSymbol(long id, long typeId, int ordinal, String name, String descriptor, Optional<String> returnType, String modifiers, boolean constructor, int startOffset, int endOffset, int startLine, int endLine) {
    public MethodSymbol {
        returnType = Optional.ofNullable(returnType).orElseThrow(() -> new NullPointerException("returnType"));
    }
}
