package dev.mcdevmcp.analysis.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.constant.ClassDesc;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TypeResolutionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void classFileCatalogUsesFinalJdkApiAndPreservesBinaryHierarchy() throws Exception {
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("catalog.jar"), Map.of("catalog/Outer.java", "package catalog; public class Outer { public static final class Inner extends java.util.ArrayList<String> implements java.io.Serializable {} }"));

        ClassFileTypeCatalog catalog = ClassFileTypeCatalog.read(jar);
        ClassFileType inner = catalog.require("catalog.Outer$Inner");

        assertEquals(ClassDesc.of("catalog.Outer$Inner"), inner.descriptor());
        assertEquals(Optional.of(ClassDesc.of("java.util.ArrayList")), inner.superclass());
        assertEquals(List.of(ClassDesc.of("java.io.Serializable")), inner.interfaces());
        assertTrue(inner.nestHost().isPresent());
        assertFalse(catalog.contains("module-info"));
    }

    @Test
    void classFileCatalogRejectsDuplicateLogicalEntries() throws Exception {
        Path original = IndexerTestSupport.createJar(temporaryDirectory.resolve("original.jar"), Map.of("duplicate/Type.java", "package duplicate; public class Type {}"));
        Path duplicate = temporaryDirectory.resolve("duplicate.jar");
        byte[] classBytes;
        try (var zip = new java.util.zip.ZipFile(original.toFile())) {
            classBytes = zip.getInputStream(zip.getEntry("duplicate/Type.class")).readAllBytes();
        }
        try (var output = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(duplicate))) {
            for (String name : List.of("duplicate/Type.class", "other/Name.class")) {
                output.putNextEntry(new java.util.jar.JarEntry(name));
                output.write(classBytes);
                output.closeEntry();
            }
        }

        IOException failure = assertThrows(IOException.class, () -> ClassFileTypeCatalog.read(duplicate));
        assertTrue(failure.getMessage().contains("duplicate.Type"));
    }
}
