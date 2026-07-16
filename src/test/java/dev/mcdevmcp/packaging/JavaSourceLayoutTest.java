package dev.mcdevmcp.packaging;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaSourceLayoutTest {
    @TempDir
    Path temporaryDirectory;
    
    private static void assertSourceLayout(Path sourceRoot) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AssertionError("A system Java compiler is required to inspect Java source layout.");
        }
        
        Path root = sourceRoot.toAbsolutePath().normalize();
        List<Path> sources = javaSourcesUnder(root);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null, fileManager.getJavaFileObjectsFromPaths(sources));
            List<CompilationUnitTree> compilationUnits = new ArrayList<>();
            task.parse().forEach(compilationUnits::add);
            for (CompilationUnitTree unit : compilationUnits) {
                Path source = Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
                String expectedPackage = expectedPackage(root, source);
                String declaredPackage = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                if (!expectedPackage.equals(declaredPackage)) {
                    throw new AssertionError(source + ": package path mismatch: expected '" + expectedPackage + "' but declared '" + declaredPackage + "'.\nJavac diagnostics:\n" + renderDiagnostics(diagnostics));
                }
                List<ClassTree> declarations = unit.getTypeDecls().stream().filter(type -> isNamedTopLevelDeclaration(type.getKind())).map(ClassTree.class::cast).toList();
                long expectedDeclarations = isPackageOrModuleInfo(source) ? 0 : 1;
                if (declarations.size() != expectedDeclarations) {
                    throw new AssertionError(source + ": expected " + expectedDeclarations + " named top-level declaration(s) but found " + declarations.size() + ".\nJavac diagnostics:\n" + renderDiagnostics(diagnostics));
                }
                if (expectedDeclarations == 1) {
                    String filename = source.getFileName().toString();
                    String expectedFilename = declarations.getFirst().getSimpleName() + ".java";
                    if (!filename.equals(expectedFilename)) {
                        throw new AssertionError(source + ": filename/simple-name mismatch: expected '" + expectedFilename + "' but found '" + filename + "'.\nJavac diagnostics:\n" + renderDiagnostics(diagnostics));
                    }
                }
            }
        }
    }
    
    private static boolean isNamedTopLevelDeclaration(Tree.Kind kind) {
        return kind == Tree.Kind.CLASS || kind == Tree.Kind.INTERFACE || kind == Tree.Kind.ENUM || kind == Tree.Kind.RECORD || kind == Tree.Kind.ANNOTATION_TYPE;
    }
    
    private static boolean isPackageOrModuleInfo(Path source) {
        String filename = source.getFileName().toString();
        return filename.equals("package-info.java") || filename.equals("module-info.java");
    }
    
    private static List<Path> javaSourcesUnder(Path sourceRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".java")).sorted(Comparator.comparing(path -> sourceRoot.relativize(path).toString())).toList();
        }
    }
    
    private static String expectedPackage(Path sourceRoot, Path source) {
        Path relative = sourceRoot.relativize(source);
        Path parent = relative.getParent();
        if (parent == null) {
            return "";
        }
        StringBuilder packageName = new StringBuilder();
        for (Path segment : parent) {
            if (!packageName.isEmpty()) {
                packageName.append('.');
            }
            packageName.append(segment);
        }
        return packageName.toString();
    }
    
    private static String renderDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        if (diagnostics.getDiagnostics().isEmpty()) {
            return "<none>";
        }
        return diagnostics.getDiagnostics().stream().map(diagnostic -> diagnostic.getKind() + " " + diagnostic.getSource() + ":" + diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber() + " " + diagnostic.getMessage(Locale.ROOT)).collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }
    
    @Test
    void packagePathMismatchIsRejected() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("sources");
        Path source = sourceRoot.resolve("expected/PackageMismatch.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package declared; class PackageMismatch {}\n");
        
        AssertionError failure = assertThrows(AssertionError.class, () -> assertSourceLayout(sourceRoot));
        
        assertEquals(source.toAbsolutePath().normalize() + ": package path mismatch: expected 'expected'" + " but declared 'declared'.\nJavac diagnostics:\n<none>", failure.getMessage());
    }
    
    @Test
    void twoNamedTopLevelDeclarationsAreRejected() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("two-types");
        Path source = sourceRoot.resolve("TwoTypes.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class First {} interface Second {}\n");
        
        AssertionError failure = assertThrows(AssertionError.class, () -> assertSourceLayout(sourceRoot));
        
        assertEquals(source.toAbsolutePath().normalize() + ": expected 1 named top-level declaration(s) but found 2" + ".\nJavac diagnostics:\n<none>", failure.getMessage());
    }
    
    @Test
    void filenameSimpleNameMismatchIsRejected() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("filename-mismatch");
        Path source = sourceRoot.resolve("WrongFile.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class ActualName {}\n");
        
        AssertionError failure = assertThrows(AssertionError.class, () -> assertSourceLayout(sourceRoot));
        
        assertEquals(source.toAbsolutePath().normalize() + ": filename/simple-name mismatch: expected 'ActualName.java'" + " but found 'WrongFile.java'.\nJavac diagnostics:\n<none>", failure.getMessage());
    }
    
    @Test
    void packageAndModuleInfoMayContainZeroNamedTopLevelDeclarations() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("zero-declarations");
        Path packageInfo = sourceRoot.resolve("fixture/package-info.java");
        Files.createDirectories(packageInfo.getParent());
        Files.writeString(packageInfo, "package fixture;\n");
        Files.writeString(sourceRoot.resolve("module-info.java"), "module fixture.module {}\n");
        
        assertSourceLayout(sourceRoot);
    }
    
    @Test
    void repositoryJavaSourcesFollowTheLayoutInvariant() throws IOException {
        for (Path sourceRoot : List.of(Path.of("src/main/java"), Path.of("src/test/java"), Path.of("mcp-tool-binding/src/main/java"), Path.of("mcp-tool-binding/src/test/java"))) {
            assertSourceLayout(sourceRoot);
        }
    }
}
