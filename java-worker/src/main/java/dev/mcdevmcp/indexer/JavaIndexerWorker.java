package dev.mcdevmcp.indexer;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class JavaIndexerWorker {
    private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();
    private static final Gson GSON = new Gson();

    private JavaIndexerWorker() {
    }

    public static void main(String[] args) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                System.out.println(handleLine(line));
                System.out.flush();
            }
        }
    }

    private static String handleLine(String line) {
        Request request;
        try {
            request = parseRequest(line);
        } catch (RuntimeException e) {
            Response response = new Response(0);
            response.failures.add(new Failure("", "Invalid request JSON: " + e.getMessage()));
            return GSON.toJson(response);
        }

        Response response = new Response(request.id);
        for (String file : request.files) {
            try {
                ParsedClass parsed = parseFile(file);
                response.parsed.add(parsed);
            } catch (Exception e) {
                response.failures.add(new Failure(file, e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        return GSON.toJson(response);
    }

    private static Request parseRequest(String line) {
        Request request;
        try {
            request = GSON.fromJson(line, Request.class);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        if (request == null) {
            throw new IllegalArgumentException("missing request fields");
        }
        if (request.id == null) {
            throw new IllegalArgumentException("missing id");
        }
        if (request.files == null) {
            throw new IllegalArgumentException("missing files");
        }
        for (String file : request.files) {
            if (file == null) {
                throw new IllegalArgumentException("files must contain only strings");
            }
        }
        return request;
    }

    private static ParsedClass parseFile(String file) throws Exception {
        if (COMPILER == null) {
            throw new IllegalStateException("System Java compiler is not available");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                     COMPILER.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> fileObjects = fileManager.getJavaFileObjectsFromPaths(List.of(Path.of(file)));
            JavacTask task = (JavacTask) COMPILER.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of("-proc:none"),
                    null,
                    fileObjects
            );
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

            return ParsedClass.from(unit, topLevel, file, trees);
        }
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

    private static String simpleType(Tree type) {
        if (type == null) return "";
        return simpleType(type.toString());
    }

    private static String simpleType(String rawType) {
        String type = stripGenerics(rawType)
                .replace("...", "")
                .replace("[]", "")
                .trim();
        int dot = type.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < type.length()) {
            type = type.substring(dot + 1);
        }
        return type;
    }

    private static String stripGenerics(String value) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                if (depth > 0) depth--;
            } else if (depth == 0) {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static List<String> modifiers(ModifiersTree modifiers) {
        List<String> result = new ArrayList<>();
        for (javax.lang.model.element.Modifier modifier : modifiers.getFlags()) {
            result.add(modifier.toString());
        }
        return result;
    }

    private static void addIfMissing(List<String> values, String value) {
        if (!values.contains(value)) values.add(value);
    }

    private static final class Request {
        Integer id;
        List<String> files;
    }

    private record Failure(String file, String error) {
    }

    private static final class ParsedClass {
        private final String packageName;
        private final String className;
        private final String fullName;
        private final ClassInfo info;

        private ParsedClass(String packageName, String className, String fullName, ClassInfo info) {
            this.packageName = packageName;
            this.className = className;
            this.fullName = fullName;
            this.info = info;
        }

        static ParsedClass from(CompilationUnitTree unit, ClassTree tree, String file, Trees trees) {
            String packageName = packageName(unit);
            String className = tree.getSimpleName().toString();
            String fullName = packageName.isEmpty() ? className : packageName + "." + className;
            ClassInfo info = ClassInfo.from(unit, tree, file, trees);
            return new ParsedClass(packageName, className, fullName, info);
        }
    }

    private static final class ClassInfo {
        private final String kind;
        @SerializedName("super")
        private final String superClass;
        private final List<String> interfaces;
        private final List<FieldInfo> fields;
        private final List<MethodInfo> methods;
        private final String sourcePath;

        private ClassInfo(
                String kind,
                String superClass,
                List<String> interfaces,
                List<FieldInfo> fields,
                List<MethodInfo> methods,
                String sourcePath
        ) {
            this.kind = kind;
            this.superClass = superClass;
            this.interfaces = interfaces;
            this.fields = fields;
            this.methods = methods;
            this.sourcePath = sourcePath;
        }

        static ClassInfo from(CompilationUnitTree unit, ClassTree tree, String file, Trees trees) {
            String kind = kindOf(tree);
            String superClass = tree.getExtendsClause() == null ? null : simpleType(tree.getExtendsClause());
            List<String> interfaces = new ArrayList<>();
            for (Tree iface : tree.getImplementsClause()) {
                interfaces.add(simpleType(iface));
            }

            SourcePositions positions = trees.getSourcePositions();
            LineMap lineMap = unit.getLineMap();
            DirectMemberScanner scanner = new DirectMemberScanner(unit, kind, positions, lineMap);
            for (Tree member : tree.getMembers()) {
                scanner.scan(member, null);
            }

            return new ClassInfo(kind, superClass, interfaces, scanner.fields, scanner.methods, file.replace('\\', '/'));
        }
    }

    private static final class DirectMemberScanner extends TreeScanner<Void, Void> {
        private final List<FieldInfo> fields = new ArrayList<>();
        private final List<MethodInfo> methods = new ArrayList<>();
        private final CompilationUnitTree unit;
        private final boolean interfaceMember;
        private final SourcePositions positions;
        private final LineMap lineMap;

        private DirectMemberScanner(
                CompilationUnitTree unit,
                String kind,
                SourcePositions positions,
                LineMap lineMap
        ) {
            this.unit = unit;
            this.interfaceMember = "interface".equals(kind);
            this.positions = positions;
            this.lineMap = lineMap;
        }

        @Override
        public Void visitVariable(VariableTree variable, Void unused) {
            fields.add(FieldInfo.from(variable, interfaceMember));
            return null;
        }

        @Override
        public Void visitMethod(MethodTree method, Void unused) {
            if (!"<init>".contentEquals(method.getName())) {
                methods.add(MethodInfo.from(unit, method, positions, lineMap));
            }
            return null;
        }

        @Override
        public Void visitBlock(BlockTree block, Void unused) {
            return null;
        }

        @Override
        public Void visitClass(ClassTree nested, Void unused) {
            return null;
        }
    }

    private static final class FieldInfo {
        private final String name;
        private final String type;
        private final List<String> modifiers;

        private FieldInfo(String name, String type, List<String> modifiers) {
            this.name = name;
            this.type = type;
            this.modifiers = modifiers;
        }

        static FieldInfo from(VariableTree variable, boolean interfaceMember) {
            List<String> modifiers = modifiers(variable.getModifiers());
            if (interfaceMember) {
                addIfMissing(modifiers, "public");
                addIfMissing(modifiers, "static");
                addIfMissing(modifiers, "final");
            }
            return new FieldInfo(variable.getName().toString(), simpleType(variable.getType()), modifiers);
        }
    }

    private static final class MethodInfo {
        private final String name;
        private final String returnType;
        private final List<ParamInfo> params;
        private final List<String> modifiers;
        private final long lineStart;
        private final long lineEnd;

        private MethodInfo(
                String name,
                String returnType,
                List<ParamInfo> params,
                List<String> modifiers,
                long lineStart,
                long lineEnd
        ) {
            this.name = name;
            this.returnType = returnType;
            this.params = params;
            this.modifiers = modifiers;
            this.lineStart = lineStart;
            this.lineEnd = lineEnd;
        }

        static MethodInfo from(
                CompilationUnitTree unit,
                MethodTree method,
                SourcePositions positions,
                LineMap lineMap
        ) {
            List<ParamInfo> params = new ArrayList<>();
            for (VariableTree param : method.getParameters()) {
                params.add(new ParamInfo(param.getName().toString(), simpleType(param.getType())));
            }

            long start = positions.getStartPosition(unit, method);
            long end = positions.getEndPosition(unit, method);
            long lineStart = start < 0 ? 0 : lineMap.getLineNumber(start);
            long lineEnd = end < 0 ? lineStart : lineMap.getLineNumber(end);

            return new MethodInfo(
                    method.getName().toString(),
                    method.getReturnType() == null ? "" : simpleType(method.getReturnType()),
                    params,
                    modifiers(method.getModifiers()),
                    lineStart,
                    lineEnd
            );
        }
    }

    private record ParamInfo(String name, String type) {
    }

    private static final class Response {
        private final int id;
        private final List<ParsedClass> parsed = new ArrayList<>();
        private final List<Failure> failures = new ArrayList<>();

        private Response(int id) {
            this.id = id;
        }
    }
}
