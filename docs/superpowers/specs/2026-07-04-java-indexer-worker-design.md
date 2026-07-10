# Java Indexer Worker Design

## Context

The current symbol indexer is orchestrated in TypeScript. It discovers Java source files, parses each file into a `ParsedClass` shape, groups classes by package, writes versioned package JSON, and writes an index manifest.

There are currently two parser paths:

- `regex`: the legacy default parser in `src/indexer/parser.ts`.
- `ast`: the opt-in `java-parser` parser in `src/indexer/parser-ast.ts`, run through bounded Node worker processes from `src/indexer/parse-worker.ts`.

The AST backend improves correctness over regex parsing, but if an AST worker crashes on a single file the indexer falls back to regex for that file. That fallback conflicts with the accuracy goal because an index stamped as AST-derived can still contain regex-derived class summaries.

The project already depends on Java-facing workflows for decompilation and callgraph generation, and Minecraft sources are Java. A pure Java parser worker is the best fit for removing the regex fallback while keeping the Node CLI and existing index format stable. The same Java requirement should be aligned across indexing and callgraph workflows.

## Goals

- Replace the current TypeScript `java-parser` AST backend with a pure Java parser worker.
- Preserve the existing TypeScript orchestration and versioned JSON index format.
- Ensure the Java backend never falls back to regex.
- Leave regex available only as an explicit legacy/debug backend.
- Make parser failures visible and deterministic.
- Keep packaging and runtime requirements simple for users.
- Raise the minimum supported Java version to 25.
- Replace the java-callgraph2 runtime clone/build path with a focused Java 25 callgraph worker.
- Preserve the full `mc_find_refs` user-facing behavior.

## Non-Goals

- Do not rewrite the full indexer orchestration in Java.
- Do not change the package index JSON schema unless failure metadata requires a manifest extension.
- Do not introduce Kotlin or a Kotlin runtime.
- Do not add a third-party Java parser dependency until JDK compiler APIs prove insufficient.
- Do not implement broad java-callgraph2 features that `mc_find_refs` does not use, such as UML output, annotations reports, generic metadata reports, variable value analysis, plugin filters, or WAR/JMOD analysis.
- Do not change callgraph query semantics as part of this work.

## Architecture

TypeScript remains the owner of indexing orchestration:

- Find Java source files.
- Choose the parser backend.
- Start and supervise parser workers.
- Preserve deterministic ordering.
- Merge classes into per-package indexes.
- Write package JSON files.
- Write the manifest.
- Report progress.

The parsing implementation moves into a small pure Java worker under a new indexer worker directory. The worker parses source files with JDK compiler APIs:

- `javax.tools.JavaCompiler`
- `com.sun.source.util.JavacTask`
- `com.sun.source.tree.*`

The Java worker emits parsed class summaries that match the existing `ParsedClass` / `ClassInfo` shape. TypeScript receives those summaries and continues to write the existing index files.

## Parser Backends

The backend model should become:

```text
java   -> default accuracy backend, implemented by the Java worker
regex  -> explicit legacy/debug backend only
```

The current `ast` backend is removed rather than retained as a long-term option. Existing references to `MCDEV_AST_PARSER` should be migrated to a clearer backend selector: `MCDEV_INDEXER=java|regex`.

The Java backend should become the default for new index builds. Regex should require an explicit `MCDEV_INDEXER=regex` selection.

Regex must not be used as a fallback when the Java backend is selected.

## Java Worker Protocol

Use a long-lived worker process instead of one Java process per file. TypeScript sends batches of source file paths to the worker and receives structured results.

A simple line-delimited protocol is sufficient:

- Request: JSON object containing request id and file paths.
- Response: JSON object containing request id, parsed classes, and parse failures.
- Fatal response: JSON object containing request id and worker-level error.

The protocol should not include raw source text. The worker reads files from disk using paths supplied by TypeScript.

## Parsed Output

For each Java file, the worker should produce one top-level class summary:

- package name
- simple class name
- full class name
- class kind: class, interface, enum, or record
- superclass simple name when applicable
- implemented or extended interfaces
- direct fields
- direct methods
- method parameter names and simple types
- method line start and line end
- source path

The worker should match the current indexer's intent: one top-level type per source file, with direct members only. Nested classes should not pollute the outer type's fields or methods.

## Failure Policy

The Java backend should be strict by default. A Java parse failure should fail the rebuild instead of silently producing a partial index.

If a skip mode is added, it must be explicit and visible:

- It must be opt-in.
- Skipped files must be recorded in the manifest.
- The manifest must distinguish a clean Java index from a Java index with skipped files.
- Tooling should be able to warn users when an active index has skipped files.

Regex is not an allowed recovery path for Java backend failures.

## Build and Packaging

The Java worker should be buildable from the existing Node package workflow. The simplest path is:

- Store Java sources in the repository.
- Compile them during `npm run build` or a dedicated prebuild step.
- Copy compiled classes or a small jar into `dist`.
- Spawn the worker with the user's `java` command.

Because JDK compiler APIs are required, the Java backend needs a JDK, not only a JRE. Java 25 is the minimum supported runtime for the worker and the callgraph workflow. The CLI should detect a missing or unsuitable Java runtime and return a clear setup error before indexing or callgraph generation begins.

## Callgraph Replacement

Callgraph generation should move from java-callgraph2 to a focused Java 25 worker backed by the JDK Class-File API (`java.lang.classfile`). The worker should scan the Minecraft client jar class files, inspect method code, and emit method invocation edges for the existing SQLite `calls` table.

The replacement must fully satisfy the current `mc_find_refs` contract:

- `direction: "callers"` returns methods whose bytecode invokes the requested class and method.
- `direction: "callees"` returns methods invoked by the requested class and method.
- Results preserve caller class, caller method, caller descriptor, callee class, callee method, callee descriptor, and best-effort source line number.
- The generated SQLite schema remains compatible with `src/callgraph/query.ts`.
- Existing `findCallers()`, `findCallees()`, `searchMethods()`, and `getCallgraphStats()` consumers continue to work.

The Java worker only needs to support the data needed by those tools. It does not need to reproduce java-callgraph2's broader static-analysis reports.

The optimized database path should:

- Avoid cloning or building java-callgraph2.
- Stream worker output into SQLite instead of reading a full intermediate callgraph file into memory.
- Create the `calls` table before inserts.
- Insert parsed rows in explicit batches.
- Create `idx_callee` and `idx_caller` after all rows are inserted.
- Preserve the existing on-disk SQLite schema and query behavior.

## Testing

Tests should cover:

- Java worker parses records, sealed types, interfaces, constants, default methods, generics, varargs, and multi-line declarations.
- Java callgraph worker scans a jar/class fixture and produces caller/callee rows that satisfy `mc_find_refs`.
- Java backend does not invoke regex on parse failure.
- Java backend fails the rebuild on parse failure by default.
- Regex backend still works when explicitly selected.
- Manifest records the selected backend as `java` or `regex`.
- Existing package index loading remains compatible.

Use existing parser AST tests as the starting corpus and migrate them to target the Java worker.

## Migration

Implementation should remove:

- `src/indexer/parser-ast.ts`
- `src/indexer/parse-worker.ts`
- `java-parser` dependency from `package.json`

Implementation should update:

- `src/indexer/parser.ts` backend selection.
- `src/indexer/index.ts` worker orchestration.
- README parser documentation.
- README callgraph documentation.
- Manifest typing for the new backend value.
- Tests that currently assert AST worker behavior.
- `src/callgraph/index.ts` to remove java-callgraph2 cloning/building and use the Java callgraph worker.

Existing regex-built and AST-built indexes should remain readable. The manifest warning logic should treat old `ast` manifests as valid but stale when the server is configured to use the new Java backend.

## Decisions

- Java becomes the default backend for new index builds.
- Regex remains available only through `MCDEV_INDEXER=regex`.
- Java 25 is the minimum supported runtime for the worker and callgraph generation.
- java-callgraph2 is replaced by a focused Java 25 Class-File API worker for `mc_find_refs`.
- Explicit skip mode is deferred until real Java parser failures are observed.
