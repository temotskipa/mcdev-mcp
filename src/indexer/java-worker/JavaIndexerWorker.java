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
import java.util.Set;

public class JavaIndexerWorker {
    private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

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
            request = JsonParser.parseRequest(line);
        } catch (RuntimeException e) {
            Response response = new Response(0);
            response.failures.add(new Failure("", "Invalid request JSON: " + e.getMessage()));
            return response.toJson();
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
        return response.toJson();
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

    private record Request(int id, List<String> files) {
    }

    private record Failure(String file, String error) {
        String toJson() {
            return "{\"file\":\"" + escape(file) + "\",\"error\":\"" + escape(error) + "\"}";
        }
    }

    private static final class ParsedClass {
        final String packageName;
        final String className;
        final String fullName;
        final ClassInfo info;

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

        String toJson() {
            return "{"
                    + "\"packageName\":\"" + escape(packageName) + "\","
                    + "\"className\":\"" + escape(className) + "\","
                    + "\"fullName\":\"" + escape(fullName) + "\","
                    + "\"info\":" + info.toJson()
                    + "}";
        }
    }

    private static final class ClassInfo {
        final String kind;
        final String superClass;
        final List<String> interfaces;
        final List<FieldInfo> fields;
        final List<MethodInfo> methods;
        final String sourcePath;

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

        String toJson() {
            return "{"
                    + "\"kind\":\"" + escape(kind) + "\","
                    + "\"super\":" + nullableString(superClass) + ","
                    + "\"interfaces\":" + stringArray(interfaces) + ","
                    + "\"fields\":" + jsonArray(fields) + ","
                    + "\"methods\":" + jsonArray(methods) + ","
                    + "\"sourcePath\":\"" + escape(sourcePath) + "\""
                    + "}";
        }
    }

    private static final class DirectMemberScanner extends TreeScanner<Void, Void> {
        final List<FieldInfo> fields = new ArrayList<>();
        final List<MethodInfo> methods = new ArrayList<>();
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

    private static final class FieldInfo implements JsonWritable {
        final String name;
        final String type;
        final List<String> modifiers;

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

        public String toJson() {
            return "{"
                    + "\"name\":\"" + escape(name) + "\","
                    + "\"type\":\"" + escape(type) + "\","
                    + "\"modifiers\":" + stringArray(modifiers)
                    + "}";
        }
    }

    private static final class MethodInfo implements JsonWritable {
        final String name;
        final String returnType;
        final List<ParamInfo> params;
        final List<String> modifiers;
        final long lineStart;
        final long lineEnd;

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

        public String toJson() {
            return "{"
                    + "\"name\":\"" + escape(name) + "\","
                    + "\"returnType\":\"" + escape(returnType) + "\","
                    + "\"params\":" + jsonArray(params) + ","
                    + "\"modifiers\":" + stringArray(modifiers) + ","
                    + "\"lineStart\":" + lineStart + ","
                    + "\"lineEnd\":" + lineEnd
                    + "}";
        }
    }

    private record ParamInfo(String name, String type) implements JsonWritable {
        public String toJson() {
            return "{"
                    + "\"name\":\"" + escape(name) + "\","
                    + "\"type\":\"" + escape(type) + "\""
                    + "}";
        }
    }

    private static final class Response {
        final int id;
        final List<ParsedClass> parsed = new ArrayList<>();
        final List<Failure> failures = new ArrayList<>();

        private Response(int id) {
            this.id = id;
        }

        String toJson() {
            return "{"
                    + "\"id\":" + id + ","
                    + "\"parsed\":" + parsedArray() + ","
                    + "\"failures\":" + failureArray()
                    + "}";
        }

        private String parsedArray() {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < parsed.size(); i++) {
                if (i > 0) out.append(',');
                out.append(parsed.get(i).toJson());
            }
            return out.append(']').toString();
        }

        private String failureArray() {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < failures.size(); i++) {
                if (i > 0) out.append(',');
                out.append(failures.get(i).toJson());
            }
            return out.append(']').toString();
        }
    }

    private interface JsonWritable {
        String toJson();
    }

    private static <T extends JsonWritable> String jsonArray(List<T> items) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) out.append(',');
            out.append(items.get(i).toJson());
        }
        return out.append(']').toString();
    }

    private static String stringArray(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            out.append('"').append(escape(values.get(i))).append('"');
        }
        return out.append(']').toString();
    }

    private static String nullableString(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    private static void addIfMissing(List<String> values, String value) {
        if (!values.contains(value)) values.add(value);
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    private static final class JsonParser {
        private final String input;
        private int pos;

        private JsonParser(String input) {
            this.input = input;
        }

        static Request parseRequest(String input) {
            JsonParser parser = new JsonParser(input);
            return parser.parseRequest();
        }

        private Request parseRequest() {
            Integer id = null;
            List<String> files = null;

            skipWhitespace();
            expect('{');
            skipWhitespace();
            if (consume('}')) {
                throw error("missing request fields");
            }

            while (true) {
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();

                if ("id".equals(key)) {
                    id = parseInt();
                } else if ("files".equals(key)) {
                    files = parseStringArray();
                } else {
                    skipValue();
                }

                skipWhitespace();
                if (consume('}')) break;
                expect(',');
                skipWhitespace();
            }
            skipWhitespace();
            if (pos != input.length()) {
                throw error("unexpected trailing content");
            }
            if (id == null) {
                throw error("missing id");
            }
            if (files == null) {
                throw error("missing files");
            }
            return new Request(id, files);
        }

        private List<String> parseStringArray() {
            List<String> result = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (consume(']')) return result;
            while (true) {
                result.add(parseString());
                skipWhitespace();
                if (consume(']')) break;
                expect(',');
                skipWhitespace();
            }
            return result;
        }

        private int parseInt() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                pos++;
            }
            if (start == pos || (input.charAt(start) == '-' && start + 1 == pos)) {
                throw error("expected integer");
            }
            return Integer.parseInt(input.substring(start, pos));
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (pos < input.length()) {
                char ch = input.charAt(pos++);
                if (ch == '"') {
                    return out.toString();
                }
                if (ch != '\\') {
                    out.append(ch);
                    continue;
                }
                if (pos >= input.length()) {
                    throw error("unterminated escape");
                }
                char escaped = input.charAt(pos++);
                switch (escaped) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(parseUnicode());
                    default -> throw error("invalid escape");
                }
            }
            throw error("unterminated string");
        }

        private char parseUnicode() {
            if (pos + 4 > input.length()) {
                throw error("short unicode escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                char ch = input.charAt(pos++);
                int digit = Character.digit(ch, 16);
                if (digit < 0) {
                    throw error("invalid unicode escape");
                }
                value = (value << 4) + digit;
            }
            return (char) value;
        }

        private void skipValue() {
            char ch = peek();
            if (ch == '"') {
                parseString();
            } else if (ch == '{') {
                skipObject();
            } else if (ch == '[') {
                skipArray();
            } else if (ch == '-' || Character.isDigit(ch)) {
                parseInt();
            } else if (startsWith("true")) {
                pos += 4;
            } else if (startsWith("false")) {
                pos += 5;
            } else if (startsWith("null")) {
                pos += 4;
            } else {
                throw error("unexpected value");
            }
        }

        private void skipObject() {
            expect('{');
            skipWhitespace();
            if (consume('}')) return;
            while (true) {
                parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                skipValue();
                skipWhitespace();
                if (consume('}')) return;
                expect(',');
                skipWhitespace();
            }
        }

        private void skipArray() {
            expect('[');
            skipWhitespace();
            if (consume(']')) return;
            while (true) {
                skipValue();
                skipWhitespace();
                if (consume(']')) return;
                expect(',');
                skipWhitespace();
            }
        }

        private boolean startsWith(String value) {
            return input.startsWith(value, pos);
        }

        private char peek() {
            if (pos >= input.length()) {
                throw error("unexpected end of input");
            }
            return input.charAt(pos);
        }

        private boolean consume(char expected) {
            if (pos < input.length() && input.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("expected '" + expected + "'");
            }
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        private RuntimeException error(String message) {
            return new IllegalArgumentException(message + " at offset " + pos);
        }
    }
}
