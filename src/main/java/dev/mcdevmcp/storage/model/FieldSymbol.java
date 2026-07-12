package dev.mcdevmcp.storage.model;

@SuppressWarnings("unused")
public record FieldSymbol(long id, long typeId, int ordinal, String name, String type, String modifiers, int startOffset, int endOffset, int startLine, int endLine) {
}
