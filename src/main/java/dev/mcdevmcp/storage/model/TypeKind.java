package dev.mcdevmcp.storage.model;

import java.util.Locale;
import java.util.Objects;

public enum TypeKind {
    CLASS("class"), INTERFACE("interface"), ENUM("enum"), RECORD("record"), ANNOTATION("annotation");
    
    private final String wireName;
    
    TypeKind(String wireName) {
        this.wireName = wireName;
    }
    
    public static TypeKind fromWireName(String wireName) {
        Objects.requireNonNull(wireName, "wireName");
        String normalized = wireName.toLowerCase(Locale.ROOT);
        for (TypeKind kind : values()) {
            if (kind.wireName.equals(normalized)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unsupported type kind: " + wireName);
    }
    
    public String wireName() {
        return wireName;
    }
    
    @Override
    public String toString() {
        return wireName;
    }
}
