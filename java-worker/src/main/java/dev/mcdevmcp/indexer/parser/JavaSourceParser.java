package dev.mcdevmcp.indexer.parser;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import dev.mcdevmcp.indexer.model.ClassInfo;
import dev.mcdevmcp.indexer.model.ParsedClass;
import dev.mcdevmcp.indexer.protocol.SourceFile;

import javax.tools.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class JavaSourceParser {
    private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();
    
    private static ParsedClass parsedClass(CompilationUnitTree unit, ClassTree tree, SourceFile file, Trees trees) {
        String packageName = packageName(unit);
        String className = tree.getSimpleName().toString();
        String fullName = packageName.isEmpty() ? className : packageName + "." + className;
        ClassInfo info = classInfo(unit, tree, file, trees);
        return new ParsedClass(packageName, className, fullName, info);
    }
    
    private static ClassInfo classInfo(CompilationUnitTree unit, ClassTree tree, SourceFile file, Trees trees) {
        String kind = kindOf(tree);
        String superClass = tree.getExtendsClause() == null ? null : TypeNames.simpleType(tree.getExtendsClause());
        List<String> interfaces = new ArrayList<>();
        for (Tree iface : tree.getImplementsClause()) {
            interfaces.add(TypeNames.simpleType(iface));
        }
        
        SourcePositions positions = trees.getSourcePositions();
        LineMap lineMap = unit.getLineMap();
        DirectMemberScanner scanner = new DirectMemberScanner(unit, kind, positions, lineMap);
        for (Tree member : tree.getMembers()) {
            scanner.scan(member, null);
        }
        
        return new ClassInfo(kind, superClass, interfaces, scanner.fields(), scanner.methods(), file.normalizedPath());
    }
    
    private static String firstError(DiagnosticCollector<JavaFileObject> diagnostics) {
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                return diagnostic.getMessage(Locale.ROOT);
            }
        }
        return null;
    }
    
    private static ClassTree firstTopLevelType(CompilationUnitTree unit) {
        for (Tree declaration : unit.getTypeDecls()) {
            if (declaration instanceof ClassTree classTree) {
                return classTree;
            }
        }
        return null;
    }
    
    private static String kindOf(ClassTree tree) {
        return switch (tree.getKind()) {
            case INTERFACE, ANNOTATION_TYPE -> "interface";
            case ENUM -> "enum";
            case RECORD -> "record";
            default -> "class";
        };
    }
    
    private static String packageName(CompilationUnitTree unit) {
        return unit.getPackageName() == null ? "" : unit.getPackageName().toString();
    }
    
    public ParsedClass parseFile(SourceFile file) throws Exception {
        if (COMPILER == null) {
            throw new IllegalStateException("System Java compiler is not available");
        }
        
        Path path = Path.of(file.path());
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = COMPILER.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> fileObjects = fileManager.getJavaFileObjectsFromPaths(List.of(path));
            JavacTask task = (JavacTask) COMPILER.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null, fileObjects);
            Trees trees = Trees.instance(task);
            List<CompilationUnitTree> units = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                units.add(unit);
            }
            
            String parseError = firstError(diagnostics);
            if (parseError != null) {
                throw new IllegalArgumentException(parseError);
            }
            if (units.isEmpty()) {
                throw new IllegalArgumentException("No compilation unit parsed");
            }
            
            CompilationUnitTree unit = units.get(0);
            ClassTree topLevel = firstTopLevelType(unit);
            if (topLevel == null) {
                throw new IllegalArgumentException("No top-level type found");
            }
            
            return parsedClass(unit, topLevel, file, trees);
        }
    }
}