package dev.mcdevmcp.analysis.index;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import javax.lang.model.element.*;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.tools.*;
import java.io.IOException;
import java.lang.constant.ClassDesc;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

public final class JavacSourceParser {
    ParsedIndex parse(IndexRequest request, ClassFileTypeCatalog catalog, CompilerClasspath classpath, SourceCorpus discovered) throws IndexBuildException, InterruptedException {
        SourceCorpus corpus = preflight(request, classpath, discovered);
        List<DecodedSource> typeSources = corpus.sources().stream().filter(source -> !source.topLevelNames().isEmpty()).toList();
        if (typeSources.isEmpty()) {
            return new ParsedIndex(List.of(), List.of());
        }
        int workerCount = Math.min(typeSources.size(), Math.min(request.threads(), Runtime.getRuntime().availableProcessors()));
        int batchSize = (typeSources.size() + workerCount - 1) / workerCount;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        List<Future<ParsedBatch>> futures = new ArrayList<>();
        ParsedIndex result;
        try {
            for (int start = 0; start < typeSources.size(); start += batchSize) {
                int end = Math.min(start + batchSize, typeSources.size());
                List<DecodedSource> batch = List.copyOf(typeSources.subList(start, end));
                futures.add(executor.submit(() -> parseBatch(request, catalog, classpath, corpus, batch)));
            }
            List<ParsedType> types = new ArrayList<>();
            List<IndexDiagnostic> diagnostics = new ArrayList<>();
            for (Future<ParsedBatch> future : futures) {
                request.cancellation().throwIfCancelled();
                ParsedBatch batch = get(future);
                types.addAll(batch.types());
                diagnostics.addAll(batch.diagnostics());
            }
            result = new ParsedIndex(types, diagnostics);
        } catch (IndexBuildException | InterruptedException | RuntimeException | Error failure) {
            terminateSuppressing(executor, futures, failure);
            throw failure;
        }
        terminate(executor, futures);
        return result;
    }

    private static ParsedBatch get(Future<ParsedBatch> future) throws IndexBuildException, InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IndexBuildException buildException) {
                throw buildException;
            }
            if (cause instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            throw new IndexBuildException("Javac source worker failed", cause);
        } catch (CancellationException exception) {
            throw new InterruptedException("Javac source worker was cancelled");
        }
    }

    private static SourceCorpus preflight(IndexRequest request, CompilerClasspath classpath, SourceCorpus discovered) throws IndexBuildException, InterruptedException {
        request.cancellation().throwIfCancelled();
        JavaCompiler compiler = CompilerConfiguration.compiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try {
            StandardJavaFileManager standard = CompilerConfiguration.fileManager(compiler);
            try (MemorySourceFileManager manager = new MemorySourceFileManager(standard, discovered, classpath, request)) {
                List<JavaFileObject> explicit = discovered.sources().stream().map(manager::object).map(JavaFileObject.class::cast).toList();
                JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, CompilerConfiguration.options(), null, explicit);
                List<? extends CompilationUnitTree> units = stream(task.parse());
                failOnSyntaxErrors(diagnostics.getDiagnostics(), discovered);
                Map<URI, DecodedSource> declarations = new HashMap<>();
                Map<String, DecodedSource> binaryNames = new HashMap<>();
                for (CompilationUnitTree unit : units) {
                    request.cancellation().throwIfCancelled();
                    DecodedSource source = discovered.require(unit.getSourceFile().toUri());
                    String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                    List<String> names = new ArrayList<>();
                    for (Tree declaration : unit.getTypeDecls()) {
                        if (declaration instanceof ClassTree type && !type.getSimpleName().isEmpty()) {
                            String simpleName = type.getSimpleName().toString();
                            String binaryName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
                            DecodedSource previous = binaryNames.putIfAbsent(binaryName, source);
                            if (previous != null) {
                                throw new IndexBuildException("Duplicate source binary name " + binaryName + " in " + previous.absolutePath() + " and " + source.absolutePath());
                            }
                            names.add(simpleName);
                        }
                    }
                    declarations.put(source.uri(), source.withDeclarations(packageName, names));
                }
                if (declarations.size() != discovered.sources().size()) {
                    throw new IndexBuildException("Javac did not return every explicit in-memory source during preflight");
                }
                return new SourceCorpus(discovered.sources().stream().map(source -> declarations.get(source.uri())).toList());
            }
        } catch (IOException exception) {
            throw new IndexBuildException("Unable to configure Javac source preflight", exception);
        }
    }

    private static ParsedBatch parseBatch(IndexRequest request, ClassFileTypeCatalog catalog, CompilerClasspath classpath, SourceCorpus corpus, List<DecodedSource> batch) throws IndexBuildException, InterruptedException {
        request.cancellation().throwIfCancelled();
        JavaCompiler compiler = CompilerConfiguration.compiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try {
            StandardJavaFileManager standard = CompilerConfiguration.fileManager(compiler);
            try (MemorySourceFileManager manager = new MemorySourceFileManager(standard, corpus, classpath, request)) {
                List<JavaFileObject> explicit = batch.stream().map(manager::object).map(JavaFileObject.class::cast).toList();
                JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, CompilerConfiguration.options(), null, explicit);
                boolean finishAttempted = false;
                try {
                    List<? extends CompilationUnitTree> units = stream(task.parse());
                    failOnSyntaxErrors(diagnostics.getDiagnostics(), corpus);
                    Map<ClassTree, List<? extends Tree>> declaredMembers = new IdentityHashMap<>();
                    Map<Tree, SourceRange> declaredRanges = new IdentityHashMap<>();
                    SourcePositions parsedPositions = Trees.instance(task).getSourcePositions();
                    for (CompilationUnitTree unit : units) {
                        String sourceContent = corpus.require(unit.getSourceFile().toUri()).content();
                        for (Tree declaration : unit.getTypeDecls()) {
                            if (declaration instanceof ClassTree type) {
                                declaredMembers.put(type, List.copyOf(type.getMembers()));
                                declaredRanges.put(type, range(unit, type, parsedPositions, sourceContent));
                                for (Tree member : type.getMembers()) {
                                    declaredRanges.put(member, range(unit, member, parsedPositions, sourceContent));
                                    if (member instanceof MethodTree method) {
                                        for (VariableTree parameter : method.getParameters()) {
                                            declaredRanges.put(parameter, range(unit, parameter, parsedPositions, sourceContent));
                                        }
                                    }
                                }
                            }
                        }
                    }
                    task.analyze();
                    request.cancellation().throwIfCancelled();
                    Trees trees = Trees.instance(task);
                    TypeResolver resolver = new TypeResolver(task.getElements(), task.getTypes());
                    List<ParsedType> parsedTypes = new ArrayList<>();
                    Map<URI, List<OffsetRange>> executableBodies = new HashMap<>();
                    for (CompilationUnitTree unit : units) {
                        executableBodies.put(unit.getSourceFile().toUri(), new ExecutableBodyScanner(unit, trees.getSourcePositions()).scan());
                        DecodedSource source = corpus.require(unit.getSourceFile().toUri());
                        for (Tree declaration : unit.getTypeDecls()) {
                            if (declaration instanceof ClassTree type) {
                                parsedTypes.add(parseType(unit, type, declaredMembers.getOrDefault(type, List.of()), declaredRanges, source, catalog, trees, resolver));
                            }
                        }
                    }
                    finishAttempted = true;
                    finish(task);
                    return new ParsedBatch(parsedTypes, classifyDiagnostics(diagnostics.getDiagnostics(), corpus, executableBodies));
                } finally {
                    if (!finishAttempted) {
                        finish(task);
                    }
                }
            }
        } catch (IOException exception) {
            throw new IndexBuildException("Unable to configure isolated Javac worker", exception);
        }
    }

    private static ParsedType parseType(CompilationUnitTree unit, ClassTree tree, List<? extends Tree> declaredMembers, Map<Tree, SourceRange> declaredRanges, DecodedSource source, ClassFileTypeCatalog catalog, Trees trees, TypeResolver resolver) throws IndexBuildException {
        TreePath typePath = TreePath.getPath(unit, tree);
        if (!(trees.getElement(typePath) instanceof TypeElement element)) {
            throw new IndexBuildException("Unable to resolve source declaration at " + source.relativeName());
        }
        String binaryName = resolver.binaryName(element);
        ElementKind kind = element.getKind();
        if (!Set.of(ElementKind.CLASS, ElementKind.INTERFACE, ElementKind.ENUM, ElementKind.RECORD, ElementKind.ANNOTATION_TYPE).contains(kind)) {
            throw new IndexBuildException("Unsupported top-level declaration kind " + kind + " for " + binaryName);
        }
        Optional<ClassDesc> superclass;
        List<ClassDesc> interfaces;
        Optional<ClassFileType> catalogType = catalog.find(binaryName);
        if (catalogType.isPresent()) {
            superclass = catalogType.orElseThrow().superclass();
            interfaces = catalogType.orElseThrow().interfaces();
        }
        else {
            superclass = element.getSuperclass().getKind() == TypeKind.NONE ? Optional.empty() : Optional.of(resolver.erasedDescriptor(element.getSuperclass()));
            List<ClassDesc> resolvedInterfaces = new ArrayList<>();
            for (var implemented : element.getInterfaces()) {
                resolvedInterfaces.add(resolver.erasedDescriptor(implemented));
            }
            interfaces = List.copyOf(resolvedInterfaces);
        }
        List<ParsedField> fields = new ArrayList<>();
        List<ParsedMethod> methods = new ArrayList<>();
        List<VariableTree> recordComponents = new ArrayList<>();
        for (Tree member : declaredMembers) {
            if (member instanceof VariableTree variable) {
                Element memberElement = trees.getElement(TreePath.getPath(unit, variable));
                if (memberElement instanceof VariableElement field && Set.of(ElementKind.FIELD, ElementKind.ENUM_CONSTANT, ElementKind.RECORD_COMPONENT).contains(field.getKind())) {
                    fields.add(new ParsedField(fields.size(), field.getSimpleName().toString(), resolver.semanticType(field.asType()), field.getModifiers(), declaredRange(variable, declaredRanges)));
                    if (field.getKind() == ElementKind.RECORD_COMPONENT) {
                        recordComponents.add(variable);
                    }
                }
            }
            else if (member instanceof MethodTree method) {
                methods.add(parseMethod(unit, method, recordComponents, declaredRanges, methods.size(), trees, resolver));
            }
        }
        return new ParsedType(source.root(), source.relativePath(), source.packageName(), binaryName, element.getSimpleName().toString(), kind, superclass, interfaces, fields, methods, declaredRange(tree, declaredRanges));
    }

    private static ParsedMethod parseMethod(CompilationUnitTree unit, MethodTree tree, List<VariableTree> recordComponents, Map<Tree, SourceRange> declaredRanges, int ordinal, Trees trees, TypeResolver resolver) throws IndexBuildException {
        if (!(trees.getElement(TreePath.getPath(unit, tree)) instanceof ExecutableElement element)) {
            throw new IndexBuildException("Unable to resolve method declaration " + tree.getName());
        }
        boolean constructor = element.getKind() == ElementKind.CONSTRUCTOR;
        String name = constructor ? "<init>" : element.getSimpleName().toString();
        ExecutableType executableType = (ExecutableType) element.asType();
        Optional<String> returnType = constructor ? Optional.empty() : Optional.of(resolver.semanticType(executableType.getReturnType()));
        List<? extends VariableTree> parameterTrees = tree.getParameters();
        if (parameterTrees.isEmpty() && constructor && !element.getParameters().isEmpty()) {
            parameterTrees = recordComponents;
        }
        if (parameterTrees.size() != element.getParameters().size()) {
            throw new IndexBuildException("Unable to match source parameter ranges for " + name);
        }
        List<ParsedParameter> parameters = new ArrayList<>();
        for (int index = 0; index < element.getParameters().size(); index++) {
            VariableElement parameter = element.getParameters().get(index);
            VariableTree parameterTree = parameterTrees.get(index);
            parameters.add(new ParsedParameter(index, parameter.getSimpleName().toString(), resolver.semanticType(parameter.asType()), element.isVarArgs() && index == element.getParameters().size() - 1, declaredRange(parameterTree, declaredRanges)));
        }
        return new ParsedMethod(ordinal, name, resolver.methodDescriptor(executableType), returnType, element.getModifiers(), constructor, parameters, declaredRange(tree, declaredRanges));
    }

    private static SourceRange declaredRange(Tree tree, Map<Tree, SourceRange> ranges) throws IndexBuildException {
        SourceRange range = ranges.get(tree);
        if (range == null) {
            throw new IndexBuildException("No captured source range for " + tree.getKind() + " '" + tree + "'");
        }
        return range;
    }

    private static SourceRange range(CompilationUnitTree unit, Tree tree, SourcePositions positions, String sourceContent) throws IndexBuildException {
        long start = positions.getStartPosition(unit, tree);
        long end = positions.getEndPosition(unit, tree);
        if (start >= 0 && end < start && tree instanceof VariableTree variable) {
            end = recordComponentEnd(sourceContent, (int) start, variable.getName().toString());
        }
        if (start < 0 || end < start || start > Integer.MAX_VALUE || end > Integer.MAX_VALUE) {
            throw new IndexBuildException("Javac did not provide a valid source range for " + tree.getKind() + " '" + tree + "' in " + unit.getSourceFile().getName() + ": " + start + ".." + end);
        }
        long endPositionForLine = end == start ? start : end - 1;
        long startLine = unit.getLineMap().getLineNumber(start);
        long endLine = unit.getLineMap().getLineNumber(endPositionForLine);
        if (startLine < 1 || endLine < startLine || startLine > Integer.MAX_VALUE || endLine > Integer.MAX_VALUE) {
            throw new IndexBuildException("Javac did not provide a valid line range in " + unit.getSourceFile().getName());
        }
        return new SourceRange((int) start, (int) end, (int) startLine, (int) endLine);
    }

    private static int recordComponentEnd(String source, int start, String name) {
        int candidate = source.indexOf(name, start);
        while (candidate >= 0) {
            int end = candidate + name.length();
            boolean identifierStart = candidate == 0 || !Character.isJavaIdentifierPart(source.charAt(candidate - 1));
            boolean identifierEnd = end == source.length() || !Character.isJavaIdentifierPart(source.charAt(end));
            int delimiter = end;
            while (delimiter < source.length() && Character.isWhitespace(source.charAt(delimiter))) {
                delimiter++;
            }
            if (identifierStart && identifierEnd && delimiter < source.length() && (source.charAt(delimiter) == ',' || source.charAt(delimiter) == ')')) {
                return end;
            }
            candidate = source.indexOf(name, candidate + 1);
        }
        return -1;
    }

    private static List<IndexDiagnostic> classifyDiagnostics(List<Diagnostic<? extends JavaFileObject>> diagnostics, SourceCorpus corpus, Map<URI, List<OffsetRange>> executableBodies) throws IndexBuildException {
        List<IndexDiagnostic> retained = new ArrayList<>();
        List<IndexDiagnostic> fatal = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
            IndexDiagnostic converted = diagnostic(diagnostic, corpus);
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                List<OffsetRange> ranges = diagnostic.getSource() == null ? List.of() : executableBodies.getOrDefault(diagnostic.getSource().toUri(), List.of());
                boolean insideBody = diagnostic.getStartPosition() >= 0 && ranges.stream().anyMatch(range -> range.contains(diagnostic.getStartPosition(), diagnostic.getEndPosition()));
                if (!insideBody) {
                    fatal.add(converted);
                    continue;
                }
            }
            retained.add(converted);
        }
        if (!fatal.isEmpty()) {
            fatal.sort(IndexDiagnostic.ORDERING);
            throw new IndexBuildException("Fatal Javac diagnostic: " + fatal.getFirst().display());
        }
        retained.sort(IndexDiagnostic.ORDERING);
        return List.copyOf(retained);
    }

    private static void failOnSyntaxErrors(List<Diagnostic<? extends JavaFileObject>> diagnostics, SourceCorpus corpus) throws IndexBuildException {
        List<IndexDiagnostic> errors = diagnostics.stream().filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR).map(diagnostic -> diagnostic(diagnostic, corpus)).sorted(IndexDiagnostic.ORDERING).toList();
        if (!errors.isEmpty()) {
            throw new IndexBuildException("Fatal Javac syntax diagnostic: " + errors.getFirst().display());
        }
    }

    private static void terminate(ExecutorService executor, List<? extends Future<?>> futures) throws IndexBuildException, InterruptedException {
        futures.forEach(future -> future.cancel(true));
        executor.shutdownNow();
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            throw new IndexBuildException("Javac index workers did not terminate");
        }
    }

    private static void terminateSuppressing(ExecutorService executor, List<? extends Future<?>> futures, Throwable failure) {
        try {
            terminate(executor, futures);
        } catch (IndexBuildException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        } catch (InterruptedException cleanupFailure) {
            Thread.currentThread().interrupt();
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static IndexDiagnostic diagnostic(Diagnostic<? extends JavaFileObject> diagnostic, SourceCorpus corpus) {
        Optional<DecodedSource> source = diagnostic.getSource() == null ? Optional.empty() : Optional.of(corpus.require(diagnostic.getSource().toUri()));
        Path sourcePath = source.map(DecodedSource::relativePath).orElse(Path.of("compiler"));
        return new IndexDiagnostic(diagnostic.getKind(), source.map(DecodedSource::root), sourcePath, diagnostic.getStartPosition(), diagnostic.getEndPosition(), diagnostic.getLineNumber(), diagnostic.getColumnNumber(), diagnostic.getCode(), diagnostic.getMessage(Locale.ROOT));
    }

    private static <T> List<T> stream(Iterable<? extends T> values) {
        List<T> result = new ArrayList<>();
        for (T value : values) {
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static void finish(JavacTask task) throws IOException {
        stream(task.generate());
    }
}
