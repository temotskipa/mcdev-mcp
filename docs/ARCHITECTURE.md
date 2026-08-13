# Architecture

## Runtime Shape

mcdev-mcp ships as one Java 25 shaded executable JAR. The same artifact runs
the human-facing CLI and the STDIO MCP server:

```text
MCP client
  -> java -jar mcdev-mcp-<version>.jar serve
     -> official MCP Java SDK
     -> typed tool and resource catalogs
     -> static-analysis services
     -> DebugBridge WebSocket client
```

The server has no analysis worker processes and no internal serialization
protocol. Filesystem and H2 work runs on virtual threads with explicit
cancellation. DebugBridge remains an independent mod and therefore remains a
real JSON/WebSocket boundary.

## Package Boundaries

Production code lives below `dev.mcdevmcp`:

| Package | Responsibility |
|---|---|
| `app` | CLI commands, startup, and analysis orchestration. |
| `mcp` | SDK adapter, STDIO server, tool catalog, and resource catalog. |
| `tools.statictool` | Source, hierarchy, version, and reference tools. |
| `tools.runtime` | DebugBridge-backed live-game tools. |
| `analysis.index` | Javac-based source indexing and typed index models. |
| `analysis.callgraph` | JDK Class-File API scanning and graph publication. |
| `analysis.decompile` | Minecraft download, Tiny Remapper, and Vineflower. |
| `storage` | Platform paths, H2 repositories, JSONL bundles, and cleanup. |
| `bridge` | Nonblocking DebugBridge envelopes, validation, and sessions. |
| `packaging` | Deterministic MCPB metadata and packed-artifact smoke tests. |
| `support` | Environment, JSON, logging, cancellation, and version helpers. |

`mcp-tool-binding` is an internal typed binding library in the same Gradle
build. It is not a second server artifact. The root project still produces the
only release JAR.

## Analysis Pipeline

`init -v <version>` performs one owned pipeline:

1. Resolve the Mojang version metadata and download the client JAR.
2. Convert official mappings and remap with embedded Tiny Remapper.
3. Decompile the remapped JAR with embedded Vineflower.
4. Parse Java source with Javac and atomically publish an H2 symbol database.
5. Scan JVM class files with the Java Class-File API and publish a deterministic
   JSONL callgraph bundle.

Javac receives batches of source files but produces one typed logical index.
`MCDEV_INDEX_THREADS` bounds parallel indexing; it does not select an alternate
backend. Callgraph generation reads class-file instructions because invocation
edges are bytecode facts, while source declarations and locations remain the
Javac indexer's responsibility.

## Storage And Rebuilds

Each Minecraft version owns its cache and index state. The symbol database is
`symbols.mv.db`. Callgraph publication uses immutable generation directories,
checksummed JSONL data/index files, and an atomic current-generation pointer.
Writers validate candidates before publication; readers never observe a
partially replaced index.

The final Node release's package JSON indexes are legacy input, not a Java
storage format. Their presence makes status report `needs rebuild`. Users run
`clean --index -v <version>` and then `init` or `rebuild`; no SQL server or
external database service is required because H2 is embedded in the JAR.

## JSON Boundaries

The MCP SDK's `McpJsonMapper` is the single JSON abstraction. MCP messages,
DebugBridge envelopes, metadata, manifests, and JSONL records deserialize into
typed Java records or bounded generic JSON values at protocol edges. Production
code does not introduce a second JSON engine.

## Packaging And Release

The root `manifest.json` is deterministic Java-generated catalog metadata. It
contains no server command or Node runtime selector.

MCPB is the sole packaging exception. `packaging/mcpb/` owns a minimal
`bootstrap.cjs`, package metadata, and its packaging dependency. The launcher
only finds Java 25 or newer and starts the bundled release JAR. All npm commands
in `scripts/build-mcpb.ps1` run with that directory as their working directory;
nothing there is part of direct JAR execution.

Release CI builds the JAR once on Java 25, records its hash, and runs that exact
artifact on Java 25 and Java 26. The MCPB is packed around the same bytes. A
read-only verification job admits exactly three publishable assets: JAR,
checksum, and MCPB. Only the final publishing job receives release write
permission.

## Compatibility Evidence

The frozen 2.2.1 Node release is a read-only differential oracle, identified by
`contracts/node-oracle.json`. Tests clone it into ignored `.superpowers`
scratch, build it there, and verify that the source checkout remains unchanged.
It is never restored into the Java branch.

DebugBridge protocol fixtures are captured from the 2.0.0 baseline. Envelope
and endpoint compatibility is tested locally; live Minecraft behavior remains
an acceptance test against a user-launched compatible mod.
