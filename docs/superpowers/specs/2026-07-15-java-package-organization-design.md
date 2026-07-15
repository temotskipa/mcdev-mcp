# Java Package Organization Design

**Date:** 2026-07-15

**Status:** Implemented and independently reviewed, including the typed-binding/JPMS amendment

## Context

The pure-Java rewrite currently has clear top-level feature areas, but several
implementation packages are becoming flat as the rewrite grows. The current
tree has 94 production Java files. `analysis.index` contains 35 files and 42
top-level declarations, `mcp` contains 22 files, and most H2 implementation
types live directly in `storage`.

The number of files is not itself a defect. Small top-level records and helpers
are an intentional consequence of minimizing nested declarations. The
organization problem is narrower:

- stable facades and implementation details are mixed in the same package;
- MCP transport, tool binding, and resource metadata share one flat package;
- the SDK-backed argument decoder is intended for later extraction, but its
  current package does not enforce that boundary and the surrounding
  `ToolBinding` still depends on mcdev cancellation and result types;
- H2-specific lifecycle code is not visibly separated from storage values;
- a few files contain several named top-level declarations;
- `JavacSourceParser` and `AtomicH2Database` each coordinate several distinct
  responsibilities that can be extracted without changing behavior.

This reorganization applies to the Java code that exists now. The resulting
taxonomy is binding for future callgraph, decompilation, DebugBridge, tool,
packaging, benchmark, and conformance work.

## Goals

- Organize Java by cohesive feature capsules with shallow responsibility-based
  subpackages.
- Keep stable facades easy to find while preserving package-private
  implementation boundaries.
- Put every named top-level declaration in its own source file, including test
  fixtures and package-private records.
- Reduce responsibility concentration in the Javac and atomic-H2 coordinators.
- Make production and test package trees mirror each other.
- Establish an internal Gradle library boundary for the extraction-ready MCP
  argument-binding API without publishing a second artifact yet.
- Keep that library ready for a future explicit JPMS descriptor while the
  shaded application remains classpath-based.
- Preserve IntelliJ-established formatting and every existing behavior.
- Add a permanent JDK compiler-tree check for source layout rules.

## Non-Goals

- Do not change MCP schemas, JSON, CLI behavior, errors, output, cancellation,
  concurrency, indexing semantics, H2 durability, or release-JAR behavior and
  contents except for Java class package names.
- Do not modularize the shaded application, patch third-party module metadata,
  or add `--add-reads`, `--add-exports`, or another JPMS workaround while the
  MCP SDK is not module-path-valid.
- Do not introduce ArchUnit, Spring, another dependency, or a custom source
  parser.
- Do not create a package for every type category or impose an arbitrary file
  length limit.
- Do not make an implementation type public solely so a cosmetic package move
  can compile.
- Do not broadly reformat files while moving them.
- Do not modify the original `master` checkout or the preserved Bun experiment
  stash.

## Organization Principles

1. A package represents a capability with one clear purpose, not a generic
   technical layer shared by unrelated features.
2. Subpackages are added only for a coherent group of types or a stable stage
   boundary. A one-type package is normally a warning sign.
3. A feature root owns its stable facade and externally consumed domain values.
4. Package-private collaborators stay together. If a proposed split would make
   several helpers public only for access, the split is too deep.
5. A new public stage facade is permitted when it represents a genuine
   cross-package operation and hides more implementation surface than it adds.
6. Every named top-level declaration has a matching file. Nested declarations
   remain only when private, one-use, and inseparable from their owner.
7. Files are split when they contain distinct responsibilities, not when they
   cross a line-count threshold.
8. A package boundary documents ownership; a Gradle project boundary enforces
   an independently buildable and publishable dependency direction. Use the
   latter only for the deliberately extraction-ready MCP binding capsule.

## Target Package Map

```text
:mcp-tool-binding
`-- dev.mcdevmcp.mcp.binding

: (root application)
`-- dev.mcdevmcp
    |-- app
    |-- support
    |-- mcp
    |   |-- McpServerFactory
    |   |-- transport
    |   |-- tool
    |   `-- resource
    |-- analysis
    |   |-- classfile
    |   `-- index
    |       `-- pipeline
    `-- storage
        |-- PlatformPaths
        |-- h2
        `-- model
```

Every package receives a concise `package-info.java` describing its ownership
and allowed dependency direction.

### Application And Support

`dev.mcdevmcp.app` and `dev.mcdevmcp.support` retain their current roles. The
application package owns Picocli entry points. Support owns small process-wide
values and services such as environment, version, cancellation, progress,
debug logging, and SDK-mapper-backed resource reading.

Support must not depend on MCP, analysis, or storage implementation packages.
The existing `AppVersion` dependency on the application entry class remains a
known narrow exception until version metadata is decoupled by its owning task;
this package move does not broaden that exception.

### MCP

`dev.mcdevmcp.mcp` retains `McpServerFactory` as the stable composition facade.

`dev.mcdevmcp.mcp.transport` owns:

- `McpSdkAdapter`
- `StdioServer`
- `NodeParityJsonMapper`
- `EofTrackingInputStream`
- `NonClosingOutputStream`

`dev.mcdevmcp.mcp.tool` owns the application-specific tool metadata, catalog,
availability, handlers, content, results, cancellation integration, execution
policy, and `ToolBinding`. It consumes the extraction-ready argument decoder
but does not move mcdev-specific contracts into that library. Concrete
Minecraft tools continue to live under `dev.mcdevmcp.tools.*`.

`dev.mcdevmcp.mcp.resource` owns `ResourceCatalog`, `ResourceDefinition`, and
`ResourceRead`.

The factory and transport may depend on tool/resource contracts. Tool and
resource packages must not depend on transport or the application package.

Java has no friend-package visibility, so the split uses one deliberate public
construction operation per MCP capsule rather than widening individual helper
constructors. `ResourceCatalog.withMapper(...)`, the executor-aware
`ToolCatalog.load(...)`, and `McpSdkAdapter.startStdio(...)` are the stable
cross-package stage operations. `McpSdkAdapter.startStdio(...)` hides mapper
wrapping, STDIO provider construction, SDK server construction, and
`StdioServer` construction. Other adapter helpers and constructors remain
package-private or private; the public `StdioServer` lifecycle type has no
public constructor.

### Typed Binding Library And JPMS

The `mcp-tool-binding` Gradle subproject is a `java-library` extraction
candidate. Its package `dev.mcdevmcp.mcp.binding` initially owns
`ArgumentDecoder<A>` and its focused tests. The decoder converts one complete
SDK argument map through `McpJsonMapper`, then may map a wire record into a
domain record. It does not grow a field-by-field typed-getter facade. Further
binding contracts move into this subproject only after multiple tool families
prove that they are reusable without application-specific type parameters or
policy.

Production declares only the official SDK `mcp-core` module as an API
dependency because `McpJsonMapper` appears in the public decoder signature.
Tests may add the official Jackson 3 mapper module and JUnit. The subproject
must not depend on the root application project or import `dev.mcdevmcp.app`,
`support`, `mcp.tool`, analysis, storage, bridge, or concrete tool packages.
The root application has the only project dependency direction and keeps
`Cancellation`, `ToolResult`, executor selection, transport adaptation,
catalogs, and Minecraft behavior outside the library. This Gradle boundary,
rather than package naming alone, prevents the current accidental coupling from
becoming part of a future public API.

The library reserves `dev.mcdevmcp.mcp.binding` as both its future JPMS module
name and its exported API package. Until the official MCP SDK publishes valid
module metadata, its JAR declares that stable name with
`Automatic-Module-Name` but contains no `module-info.java`. The build does not
use a descriptor-patching plugin or hand-maintained descriptors for SDK
artifacts. The reviewed SDK snapshot and current upstream source derive invalid
names containing hyphens for `mcp-core` and `mcp-json-jackson3`; upstream tracks
this as [MCP Java SDK issue #560](https://github.com/modelcontextprotocol/java-sdk/issues/560).
Once the SDK is module-path-valid, a focused compatibility task may add an
explicit descriptor, export only the public binding package, and keep
implementation packages concealed. The root shaded executable remains a
classpath application and incorporates the library as an ordinary dependency.

Extraction to another repository, independent publication, and an upstream
proposal remain deferred until static and runtime tool families have proven
the API and error model. Extraction must preserve package and module identity;
the project does not claim an `io.modelcontextprotocol` namespace unless the
code is accepted upstream.

### Class-File Analysis

`dev.mcdevmcp.analysis.classfile` owns the reusable JDK Class-File API catalog:

- `ClassFileType`
- `ClassFileTypeCatalog`
- `ClassDescriptors`, replacing the narrowly named `DescriptorNames`

`ClassDescriptors` is a deliberate shared class-file boundary rather than a
helper made public for a move. The source indexer consumes this package, and the
future callgraph implementation may reuse its descriptor rules without
depending on source-indexer internals.

### Source Indexing

`dev.mcdevmcp.analysis.index` owns the stable indexing surface:

- `SourceIndexer`
- `IndexRequest`
- `IndexSummary`
- `IndexBuildException`
- `SourceRoot`

`SourceIndexer` becomes a thin facade over one deliberate public stage
boundary, `SourceIndexPipeline`, in
`dev.mcdevmcp.analysis.index.pipeline`. The pipeline package owns all current
Javac, in-memory file-manager, parsed-model, diagnostic, deterministic merge,
symbol-writing, and snapshot-validation internals.

Keeping those stages in one implementation capsule is intentional. The
`ParsedType`, `ParsedMethod`, `ParsedField`, and related records are shared
inside one atomic source-index build and are not stable application APIs.
Splitting compiler and writer into separate packages would make the whole
parsed model public only to satisfy Java package access. The single pipeline
facade avoids that expansion.

`JavacSourceParser` becomes package-private. `SourceIndexPipeline` exposes only
the complete build operation required by `SourceIndexer`; its injectable
constructor remains inside the pipeline package for focused tests.

The private `IndexRequest.SourceIdentity` record becomes a focused
package-private `SourceIdentity` file in the stable indexing package. It
remains an internal request-validation value and does not widen the public API.

### Storage

`dev.mcdevmcp.storage` retains `PlatformPaths`, the stable cache-layout value.

`dev.mcdevmcp.storage.h2` owns all current H2-specific implementation and
lifecycle types, including:

- atomic build, validation, promotion, and recovery;
- database locks and file operations;
- symbol schema and repository;
- version-state detection and index cleaning;
- H2 JDBC URL construction.

Moving the complete H2 capsule together preserves all current public and
package-private visibility. No lock, file-operation, promotion, or URL helper
becomes public for the move.

`dev.mcdevmcp.storage.model` retains persisted semantic values such as
`MinecraftVersion`, `FabricApiVersion`, symbols, namespace, and version state.

## File Decomposition

### Javac Pipeline

`JavacSourceParser` keeps batch planning and top-level parse orchestration.
Focused package-private collaborators take the distinct work it currently
contains:

- `JavacPreflight` validates syntax, accounts for every compilation unit, and
  discovers package/top-level declaration metadata.
- `JavacBatchParser` owns one isolated parse/analyze/generate compiler task.
- `JavacDeclarationReader` converts attributed compiler trees/elements into the
  immutable parsed model and owns declaration-range capture.
- `JavacDiagnostics` implements syntax and attribution diagnostic policy.
- `JavacTaskExecutor` owns futures, cancellation polling, termination, and
  suppressed cleanup failures.

Existing focused helpers such as `MemorySourceFileManager`,
`ExecutableBodyScanner`, and `TypeResolver` remain separate. Extraction must
not alter worker count, modular single-task behavior, diagnostics, source-text
sharing, compiler options, or close order.

### Symbol Snapshot

`SymbolIndexSnapshot.java` retains loading and exact validation. Each projection
record moves to its own package-private file:

- `SymbolIndexMetadata`
- `IndexedPackageSnapshot`
- `IndexedTypeSnapshot`
- `IndexedInterfaceSnapshot`
- `IndexedFieldSnapshot`
- `IndexedMethodSnapshot`
- `IndexedParameterSnapshot`

### Atomic H2 Lifecycle

`AtomicH2Database` retains transaction/build orchestration and the public
rebuild operation. Two package-private collaborators isolate file-state work:

- `H2DatabaseArtifacts` owns temporary/backup paths, companion discovery,
  stale-artifact checks, and cleanup.
- `H2DatabasePromotion` owns atomic promotion, fallback phases, validation,
  restoration, and suppressed recovery failures.

`DatabasePromotionPhase`, `DatabaseFileOperations`, and lock types remain
focused top-level declarations. Existing recovery tests define behavior; the
extraction must not reinterpret any state transition.

### Test Fixtures

Named fixtures currently declared beside tests move to their own files. This
includes SDK mapper probes, tool-binding wire/domain arguments, the counting
mapper, and H2 failure enums/helpers.

Large test files may be split by behavior when they already contain separable
suites. In particular, atomic-H2 build, promotion, and recovery cases may use
separate test classes with shared package-private fixtures. Tests are not split
merely to reduce line count.

## Visibility Rules

- Existing stable public facades and domain values remain public.
- The three MCP capsule construction operations named above are deliberate
  public stage facades; no other MCP helper is widened for package access.
- `SourceIndexPipeline` is the only new public indexing boundary.
- `ClassDescriptors` is public because it is a reusable class-file domain
  boundary shared with future callgraph work.
- `JavacSourceParser` becomes package-private.
- Parsed index records, snapshot projections, compiler file objects,
  diagnostic helpers, H2 file operations, and test fixtures remain
  package-private.
- Constructors used only for package-level dependency injection remain
  package-private and their tests move with the package.

## Source Layout Enforcement

A permanent Java test uses `JavaCompiler`/`JavacTask.parse()` and compiler-tree
APIs over every configured production and test Java source root in both Gradle
projects. It does not use regex source parsing or add a third-party architecture
dependency.

For every ordinary Java source, the test verifies:

- the declared package matches the path below its source root;
- there is exactly one named top-level declaration;
- the source filename matches that declaration's simple name.

`package-info.java` and `module-info.java` are valid zero-type exceptions.
Generated build output and Java fixture text under resources are outside the
scan. The check deliberately does not ban all nested declarations; rare private
callbacks remain a review judgment.

## Migration Sequence

1. Inventory the dirty worktree and preserve every pre-existing user formatting
   change and PR-feedback amendment. Commit independent existing work
   logically before package moves; never reset, clean, or rewrite it.
2. Convert the build to two Gradle projects, move `ArgumentDecoder` and its
   mapper/record tests into `mcp-tool-binding`, and verify the one-way project
   dependency before changing the remaining MCP packages.
3. Move application MCP production/tests mechanically and run MCP-focused
   tests.
4. Move the complete H2 capsule and mirrored tests, then run all storage tests.
5. Move the class-file catalog and add the index facade/pipeline boundary.
6. Move remaining index internals/tests and run the complete indexer suite.
7. Extract Javac, snapshot, H2, and test-file responsibilities in focused
   behavior-neutral commits.
8. Add the compiler-tree layout invariant and package documentation.
9. Update the approved rewrite design, implementation plan, and future target
   file map to use this taxonomy.
10. Regenerate the Task 5 review package from its original base through the new
   head and repeat independent review before Task 6.

Mechanical moves and responsibility extraction are not behavior changes and do
not invent artificial failing tests. Existing characterization and contract
tests run before and after each move. Any actual behavior correction discovered
during the migration follows red-green-refactor in a separate focused commit.

## Verification

After each capsule move, run its focused tests. The final gate runs:

- `:mcp-tool-binding:clean :mcp-tool-binding:test :mcp-tool-binding:jar` and
  the root application test suite;
- dependency inspection proving the library has no root-project dependency or
  app-specific imports, and `jar --describe-module` proving its stable
  automatic module name;
- full Java 25 `clean test shadowJar cutoverCheck`;
- full Java 26 `clean test shadowJar cutoverCheck`;
- exact shaded-JAR startup/STDIO checks on Java 25 and 26;
- runtime dependency and shaded-archive checks;
- the no-fallback parser audit;
- IntelliJ full rebuild;
- IntelliJ warnings-enabled inspection for every changed Java file;
- original `master` status and preserved-stash checks.

Moves preserve existing file formatting. Only newly extracted files receive
the established IntelliJ formatting, and unrelated source is never broadly
reformatted.

## Future Package Rules

Future implementation follows the same feature-capsule taxonomy:

- `mcp-tool-binding` for only SDK/JDK-based argument decoding and later-proven
  generic binding contracts
- `dev.mcdevmcp.analysis.callgraph`
- `dev.mcdevmcp.analysis.decompile`
- `dev.mcdevmcp.bridge` with subpackages only after real client/protocol seams
  exist
- `dev.mcdevmcp.tools.statictool`
- `dev.mcdevmcp.tools.runtime`
- `dev.mcdevmcp.packaging`
- dedicated benchmark and conformance source-set packages

A future task may add a subpackage only when it can name a stable capability
boundary and preserve narrow visibility. It must not create generic global
`util`, `service`, `dto`, or `impl` buckets.

## Acceptance Criteria

- The target package map is present in production and mirrored by tests.
- `mcp-tool-binding` builds independently, exposes the stable automatic module
  name `dev.mcdevmcp.mcp.binding`, and has no root-project or mcdev application
  dependency.
- The application-specific `ToolBinding`, cancellation, result, executor,
  catalog, and transport types remain outside the extraction candidate.
- MCP subpackages expose only the documented resource, tool, and transport
  stage operations; adapter/helper and lifecycle constructors remain
  non-public.
- No SDK descriptor patching or explicit project `module-info.java` is added
  before the MCP SDK becomes module-path-valid.
- Every named top-level production/test declaration has its own matching file.
- The compiler-tree source-layout check passes and runs under Gradle `check`.
- No package move widens an existing implementation helper solely for access.
- `SourceIndexer` is a thin stable facade; parsed/compiler/writer internals stay
  out of its package and out of the public domain surface.
- MCP, indexer, storage, exact-JAR, Java 25/26, cutover, archive, and IntelliJ
  gates pass without user-visible behavior changes.
- Existing IntelliJ formatting, original `master`, and the preserved stash are
  unchanged by the migration.
- The approved rewrite documents and all later tasks use the new taxonomy.
