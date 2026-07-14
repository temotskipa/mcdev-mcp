package dev.mcdevmcp.storage;

import dev.mcdevmcp.storage.model.*;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelValueTest {
    @Test
    void mapsOnlySupportedJdkElementKindsToStableH2WireNames() {
        assertEquals("class", ElementKindCodec.wireName(ElementKind.CLASS));
        assertEquals("annotation", ElementKindCodec.wireName(ElementKind.ANNOTATION_TYPE));
        assertEquals(ElementKind.RECORD, ElementKindCodec.fromWireName("record"));
        assertThrows(IllegalArgumentException.class, () -> ElementKindCodec.wireName(ElementKind.METHOD));
    }
    
    @Test
    void classSymbolsKeepStructuredValuesAndImmutableCollections() {
        var interfaces = new java.util.ArrayList<>(List.of("java.io.Closeable"));
        var symbol = new ClassSymbol(1L, SourceNamespace.FABRIC, Optional.of(new FabricApiVersion("0.120.0")), "net.fabricmc.Test", "net.fabricmc", "Test", ElementKind.CLASS, Optional.empty(), interfaces, Path.of("Test.java"), 0, 10, 1, 2);
        
        interfaces.add("java.lang.Runnable");
        assertEquals(List.of("java.io.Closeable"), symbol.interfaceBinaryNames());
        assertThrows(UnsupportedOperationException.class, () -> symbol.interfaceBinaryNames().add("x"));
        assertThrows(IllegalArgumentException.class, () -> new ClassSymbol(2L, SourceNamespace.MINECRAFT, Optional.of(new FabricApiVersion("0.120.0")), "net.minecraft.Test", "net.minecraft", "Test", ElementKind.CLASS, Optional.empty(), List.of(), Path.of("Test.java"), 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ClassSymbol(3L, SourceNamespace.FABRIC, Optional.empty(), "net.fabricmc.Missing", "net.fabricmc", "Missing", ElementKind.CLASS, Optional.empty(), List.of(), Path.of("Missing.java"), 0, 1, 1, 1));
        assertEquals(Set.of(Modifier.PUBLIC), Set.copyOf(Set.of(Modifier.PUBLIC)));
    }

    @Test
    void fabricApiVersionsAreSafeSingleFilesystemComponents() {
        assertEquals("0.120.0", new FabricApiVersion("0.120.0").value());
        assertThrows(IllegalArgumentException.class, () -> new FabricApiVersion("."));
        assertThrows(IllegalArgumentException.class, () -> new FabricApiVersion(".."));
        assertThrows(IllegalArgumentException.class, () -> new FabricApiVersion("0.120.0/escape"));
        assertThrows(IllegalArgumentException.class, () -> new FabricApiVersion("C:\\escape"));
    }
    
    @Test
    void memberSymbolsUseImmutableJdkModifierSets() {
        var field = new FieldSymbol(1L, 2L, 0, "field", "int", Set.of(Modifier.PRIVATE), 0, 1, 1, 1);
        var method = new MethodSymbol(3L, 2L, 0, "method", "()V", Optional.empty(), Set.of(Modifier.PUBLIC), false, 0, 1, 1, 1);
        var parameter = new ParameterSymbol(4L, 3L, 0, "parameter", "int", false, 0, 1, 1, 1);
        
        assertEquals(Set.of(Modifier.PRIVATE), field.modifiers());
        assertEquals(Set.of(Modifier.PUBLIC), method.modifiers());
        assertEquals("parameter", parameter.name());
        assertThrows(UnsupportedOperationException.class, () -> field.modifiers().add(Modifier.PUBLIC));
    }
}
