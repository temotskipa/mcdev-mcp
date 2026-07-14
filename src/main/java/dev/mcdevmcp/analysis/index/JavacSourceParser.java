package dev.mcdevmcp.analysis.index;

import com.sun.source.tree.*;
import com.sun.source.util.*;

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
import java.util.concurrent.atomic.AtomicBoolean;

public final class JavacSourceParser {
    private static final long CANCELLATION_POLL_MILLIS = 25;
    private final Runnable compilerStarted;
    private final AtomicBoolean compilerStartedNotified = new AtomicBoolean();
    
    public JavacSourceParser() {
        this(() -> {
        });
    }
    
    JavacSourceParser(Runnable compilerStarted) {
        this.compilerStarted = Objects.requireNonNull(compilerStarted, "compilerStarted");
    }
    
    private static <T> T get(Future<T> future, IndexRequest request) throws IndexBuildException, InterruptedException {
        while (true) {
            request.cancellation().throwIfCancelled();
            try {
                return future.get(CANCELLATION_POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
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
    }
    
    private static ParsedType parseType(CompilationUnitTree unit, ClassTree tree, List<? extends Tree> declaredMembers, Map<MethodTree, List<? extends VariableTree>> declaredMethodParameters, Map<Tree, SourceRange> declaredRanges, DecodedSource source, ClassFileTypeCatalog catalog, Trees trees, TypeResolver resolver) throws IndexBuildException {
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
        List<VariableTree> recordComponents = recordComponents(unit, element, declaredMembers, declaredRanges, trees, resolver);
        for (Tree member : declaredMembers) {
            if (member instanceof VariableTree variable) {
                Element memberElement = trees.getElement(TreePath.getPath(unit, variable));
                if (memberElement instanceof VariableElement field && Set.of(ElementKind.FIELD, ElementKind.ENUM_CONSTANT, ElementKind.RECORD_COMPONENT).contains(field.getKind())) {
                    fields.add(new ParsedField(fields.size(), field.getSimpleName().toString(), resolver.semanticType(field.asType()), field.getModifiers(), declaredRange(variable, declaredRanges)));
                }
            }
            else if (member instanceof MethodTree method) {
                methods.add(parseMethod(unit, method, declaredMethodParameters.getOrDefault(method, List.of()), recordComponents, declaredRanges, methods.size(), trees, resolver));
            }
        }
        return new ParsedType(source.root(), source.relativePath(), source.packageName(), binaryName, element.getSimpleName().toString(), kind, superclass, interfaces, fields, methods, declaredRange(tree, declaredRanges));
    }
    
    private static ParsedMethod parseMethod(CompilationUnitTree unit, MethodTree tree, List<? extends VariableTree> declaredParameters, List<VariableTree> recordComponents, Map<Tree, SourceRange> declaredRanges, int ordinal, Trees trees, TypeResolver resolver) throws IndexBuildException {
        if (!(trees.getElement(TreePath.getPath(unit, tree)) instanceof ExecutableElement element)) {
            throw new IndexBuildException("Unable to resolve method declaration " + tree.getName());
        }
        boolean constructor = element.getKind() == ElementKind.CONSTRUCTOR;
        String name = constructor ? "<init>" : element.getSimpleName().toString();
        ExecutableType executableType = (ExecutableType) element.asType();
        Optional<String> returnType = constructor ? Optional.empty() : Optional.of(resolver.semanticType(executableType.getReturnType()));
        List<? extends VariableTree> parameterTrees = declaredParameters;
        boolean compactProjection = !parameterTrees.isEmpty() && parameterTrees.stream().noneMatch(declaredRanges::containsKey);
        if (compactProjection) {
            if (!constructor || !matchesRecordComponents(element.getParameters(), recordComponents, unit, trees, resolver)) {
                throw new IndexBuildException("Javac did not provide exact supported parameter ranges for " + name);
            }
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

    private static List<VariableTree> recordComponents(CompilationUnitTree unit, TypeElement element, List<? extends Tree> declaredMembers, Map<Tree, SourceRange> declaredRanges, Trees trees, TypeResolver resolver) throws IndexBuildException {
        List<VariableTree> result = new ArrayList<>();
        for (RecordComponentElement component : element.getRecordComponents()) {
            List<VariableTree> matches = new ArrayList<>();
            for (Tree member : declaredMembers) {
                if (member instanceof VariableTree variable && declaredRanges.containsKey(variable) && variable.getName().contentEquals(component.getSimpleName())) {
                    Element candidate = trees.getElement(TreePath.getPath(unit, variable));
                    if (candidate instanceof VariableElement field && resolver.semanticType(field.asType()).equals(resolver.semanticType(component.asType()))) {
                        matches.add(variable);
                    }
                }
            }
            if (matches.size() != 1) {
                throw new IndexBuildException("Unable to match exact compiler-owned source range for record component " + component.getSimpleName());
            }
            result.add(matches.getFirst());
        }
        return List.copyOf(result);
    }

    private static boolean matchesRecordComponents(List<? extends VariableElement> parameters, List<VariableTree> components, CompilationUnitTree unit, Trees trees, TypeResolver resolver) throws IndexBuildException {
        if (parameters.size() != components.size()) {
            return false;
        }
        for (int index = 0; index < parameters.size(); index++) {
            VariableElement parameter = parameters.get(index);
            VariableTree component = components.get(index);
            Element candidate = trees.getElement(TreePath.getPath(unit, component));
            if (!(candidate instanceof VariableElement field) || !parameter.getSimpleName().contentEquals(component.getName()) || !resolver.semanticType(parameter.asType()).equals(resolver.semanticType(field.asType()))) {
                return false;
            }
        }
        return true;
    }
    
    private static SourceRange declaredRange(Tree tree, Map<Tree, SourceRange> ranges) throws IndexBuildException {
        SourceRange range = ranges.get(tree);
        if (range == null) {
            throw new IndexBuildException("No captured source range for " + tree.getKind() + " '" + tree + "'");
        }
        return range;
    }
    
    private static void captureRange(CompilationUnitTree unit, Tree tree, SourcePositions positions, Map<Tree, SourceRange> ranges) throws IndexBuildException {
        long start = positions.getStartPosition(unit, tree);
        long end = positions.getEndPosition(unit, tree);
        if (start >= 0 && end < start && tree instanceof VariableTree) {
            return;
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
        ranges.put(tree, new SourceRange((int) start, (int) end, (int) startLine, (int) endLine));
    }

    private static List<IndexDiagnostic> classifyDiagnostics(List<Diagnostic<? extends JavaFileObject>> diagnostics, SourceCorpus corpus, Map<URI, List<OffsetRange>> executableBodies, Set<URI> ownedSources) throws IndexBuildException {
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
            if (diagnostic.getSource() == null || ownedSources.contains(diagnostic.getSource().toUri())) {
                retained.add(converted);
            }
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

    private void observeParsedUnits(JavacTask task, Map<URI, CompilationUnitTree> parsedUnits) {
        task.addTaskListener(new TaskListener() {
            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() == TaskEvent.Kind.PARSE && event.getCompilationUnit() != null) {
                    parsedUnits.putIfAbsent(event.getCompilationUnit().getSourceFile().toUri(), event.getCompilationUnit());
                    if (compilerStartedNotified.compareAndSet(false, true)) {
                        compilerStarted.run();
                    }
                }
            }
        });
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
                ParsedBatch batch = get(future, request);
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
    
    private SourceCorpus preflight(IndexRequest request, CompilerClasspath classpath, SourceCorpus discovered) throws IndexBuildException, InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<SourceCorpus> future = executor.submit(() -> preflightWorker(request, classpath, discovered));
        try {
            SourceCorpus result = get(future, request);
            terminate(executor, List.of(future));
            return result;
        } catch (IndexBuildException | InterruptedException | RuntimeException | Error failure) {
            terminateSuppressing(executor, List.of(future), failure);
            throw failure;
        }
    }
    
    private SourceCorpus preflightWorker(IndexRequest request, CompilerClasspath classpath, SourceCorpus discovered) throws IndexBuildException, InterruptedException {
        request.cancellation().throwIfCancelled();
        JavaCompiler compiler = CompilerConfiguration.compiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try {
            StandardJavaFileManager standard = CompilerConfiguration.fileManager(compiler);
            try (MemorySourceFileManager manager = new MemorySourceFileManager(standard, discovered, classpath, request)) {
                List<JavaFileObject> explicit = discovered.sources().stream().map(manager::object).map(JavaFileObject.class::cast).toList();
                JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, CompilerConfiguration.options(), null, explicit);
                observeParsedUnits(task, new LinkedHashMap<>());
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
    
    private ParsedBatch parseBatch(IndexRequest request, ClassFileTypeCatalog catalog, CompilerClasspath classpath, SourceCorpus corpus, List<DecodedSource> batch) throws IndexBuildException, InterruptedException {
        request.cancellation().throwIfCancelled();
        JavaCompiler compiler = CompilerConfiguration.compiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try {
            StandardJavaFileManager standard = CompilerConfiguration.fileManager(compiler);
            try (MemorySourceFileManager manager = new MemorySourceFileManager(standard, corpus, classpath, request)) {
                List<JavaFileObject> explicit = batch.stream().map(manager::object).map(JavaFileObject.class::cast).toList();
                JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, CompilerConfiguration.options(), null, explicit);
                Map<URI, CompilationUnitTree> parsedUnits = new LinkedHashMap<>();
                observeParsedUnits(task, parsedUnits);
                boolean finishAttempted = false;
                try {
                    List<? extends CompilationUnitTree> units = stream(task.parse());
                    failOnSyntaxErrors(diagnostics.getDiagnostics(), corpus);
                    Map<ClassTree, List<? extends Tree>> declaredMembers = new IdentityHashMap<>();
                    Map<MethodTree, List<? extends VariableTree>> declaredMethodParameters = new IdentityHashMap<>();
                    Map<Tree, SourceRange> declaredRanges = new IdentityHashMap<>();
                    SourcePositions parsedPositions = Trees.instance(task).getSourcePositions();
                    for (CompilationUnitTree unit : units) {
                        for (Tree declaration : unit.getTypeDecls()) {
                            if (declaration instanceof ClassTree type) {
                                declaredMembers.put(type, List.copyOf(type.getMembers()));
                                captureRange(unit, type, parsedPositions, declaredRanges);
                                for (Tree member : type.getMembers()) {
                                    captureRange(unit, member, parsedPositions, declaredRanges);
                                    if (member instanceof MethodTree method) {
                                        List<? extends VariableTree> parameters = List.copyOf(method.getParameters());
                                        declaredMethodParameters.put(method, parameters);
                                        for (VariableTree parameter : parameters) {
                                            captureRange(unit, parameter, parsedPositions, declaredRanges);
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
                    for (CompilationUnitTree unit : units) {
                        DecodedSource source = corpus.require(unit.getSourceFile().toUri());
                        for (Tree declaration : unit.getTypeDecls()) {
                            if (declaration instanceof ClassTree type) {
                                parsedTypes.add(parseType(unit, type, declaredMembers.getOrDefault(type, List.of()), declaredMethodParameters, declaredRanges, source, catalog, trees, resolver));
                            }
                        }
                    }
                    finishAttempted = true;
                    finish(task);
                    request.cancellation().throwIfCancelled();
                    Map<URI, List<OffsetRange>> executableBodies = new HashMap<>();
                    for (CompilationUnitTree unit : parsedUnits.values()) {
                        executableBodies.put(unit.getSourceFile().toUri(), new ExecutableBodyScanner(unit, trees.getSourcePositions()).scan());
                    }
                    Set<URI> ownedSources = batch.stream().map(DecodedSource::uri).collect(java.util.stream.Collectors.toUnmodifiableSet());
                    return new ParsedBatch(parsedTypes, classifyDiagnostics(diagnostics.getDiagnostics(), corpus, executableBodies, ownedSources));
                } finally {
                    if (!finishAttempted && !Thread.currentThread().isInterrupted() && !request.cancellation().isCancelled()) {
                        finish(task);
                    }
                }
            }
        } catch (IOException exception) {
            throw new IndexBuildException("Unable to configure isolated Javac worker", exception);
        }
    }
}
