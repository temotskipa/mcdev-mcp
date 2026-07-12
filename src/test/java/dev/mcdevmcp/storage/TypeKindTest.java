package dev.mcdevmcp.storage;

import dev.mcdevmcp.storage.model.ClassSymbol;
import dev.mcdevmcp.storage.model.TypeKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TypeKindTest {
    @Test
    void typeKindWireNamesMatchSchemaConstraint() {
        assertEquals("class", TypeKind.CLASS.wireName());
        assertEquals("interface", TypeKind.INTERFACE.wireName());
        assertEquals("enum", TypeKind.ENUM.wireName());
        assertEquals("record", TypeKind.RECORD.wireName());
        assertEquals("annotation", TypeKind.ANNOTATION.wireName());
        assertEquals(TypeKind.RECORD, TypeKind.fromWireName("record"));
        assertThrows(IllegalArgumentException.class, () -> TypeKind.fromWireName("not-a-kind"));
    }

    @Test
    void classSymbolRequiresTypedKind() {
        ClassSymbol symbol = new ClassSymbol(
                1L,
                "minecraft",
                "net.minecraft.Test",
                "net.minecraft",
                "Test",
                TypeKind.CLASS,
                Optional.empty(),
                List.of(),
                Path.of("Test.java"),
                0,
                10,
                1,
                2);
        assertEquals(TypeKind.CLASS, symbol.kind());
        assertThrows(NullPointerException.class, () -> new ClassSymbol(
                1L,
                "minecraft",
                "net.minecraft.Test",
                "net.minecraft",
                "Test",
                null,
                Optional.empty(),
                List.of(),
                Path.of("Test.java"),
                0,
                10,
                1,
                2));
    }
}
