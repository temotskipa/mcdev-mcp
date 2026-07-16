package dev.mcdevmcp.analysis.index.pipeline;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import dev.mcdevmcp.analysis.classfile.ClassFileTypeCatalog;
import dev.mcdevmcp.analysis.index.IndexBuildException;
import dev.mcdevmcp.analysis.index.IndexRequest;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

final class JavacSourceParser {
    private final Runnable compilerStarted;
    private final AtomicBoolean compilerStartedNotified = new AtomicBoolean();
    
    JavacSourceParser() {
        this(() -> {
        });
    }
    
    JavacSourceParser(Runnable compilerStarted) {
        this.compilerStarted = Objects.requireNonNull(compilerStarted, "compilerStarted");
    }
    
    ParsedIndex parse(IndexRequest request, ClassFileTypeCatalog catalog, CompilerClasspath classpath, SourceCorpus discovered) throws IndexBuildException, InterruptedException {
        if (discovered.sources().isEmpty()) {
            return new ParsedIndex(List.of(), List.of());
        }
        SourceCorpus corpus = preflight(request, classpath, discovered);
        List<DecodedSource> sources = corpus.sources();
        boolean hasModuleDescriptor = sources.stream().map(DecodedSource::relativePath).map(Path::getFileName).anyMatch(Path.of("module-info.java")::equals);
        int workerCount = hasModuleDescriptor ? 1 : Math.min(sources.size(), Math.min(request.threads(), Runtime.getRuntime().availableProcessors()));
        int batchSize = (sources.size() + workerCount - 1) / workerCount;
        List<Callable<ParsedBatch>> tasks = new ArrayList<>();
        for (int start = 0; start < sources.size(); start += batchSize) {
            int end = Math.min(start + batchSize, sources.size());
            List<DecodedSource> batch = List.copyOf(sources.subList(start, end));
            tasks.add(() -> JavacBatchParser.parse(request, catalog, classpath, corpus, batch, this::observeParsedUnits));
        }
        return JavacTaskExecutor.executeAll(request, workerCount, tasks, this::assembleParsedIndex);
    }
    
    private ParsedIndex assembleParsedIndex(List<ParsedBatch> batches) {
        List<ParsedType> types = new ArrayList<>();
        List<IndexDiagnostic> diagnostics = new ArrayList<>();
        for (ParsedBatch batch : batches) {
            types.addAll(batch.types());
            diagnostics.addAll(batch.diagnostics());
        }
        return new ParsedIndex(types, diagnostics);
    }
    
    private SourceCorpus preflight(IndexRequest request, CompilerClasspath classpath, SourceCorpus discovered) throws IndexBuildException, InterruptedException {
        return JavacTaskExecutor.executeSingle(request, () -> JavacPreflight.preflight(request, classpath, discovered, this::observeParsedUnits));
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
}
