# Multi-Version Minecraft Support Implementation Plan

> **Pinned Node oracle evidence:** Historical source paths below refer to the commit recorded in
> [`contracts/node-oracle.json`](../contracts/node-oracle.json), not files in the current Java worktree.

> **Status:** Implemented. Phases 1–9 are landed and in production. Phase 10 (auto-migration) was reviewed and **abandoned** — see that section for rationale. This document is retained as design history; the API Reference at the bottom reflects the **current** shipped surface.

## Overview

This document outlines the implementation plan for supporting multiple Minecraft versions in mcdev-mcp. The system allows users to work with different Minecraft versions by explicitly setting a version before using other API tools.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Uninitialized version handling | Return error, tell AI to STOP and ask USER | Prevents unexpected long operations; gives user control |
| Version persistence | No persistence, require per session | Simpler mental model; no stale state across sessions |
| Per-call version override | Optional `version` parameter on all tools | Flexibility for advanced use cases without breaking flow |

## Architecture Changes

> The two diagrams below capture the pre-implementation **starting** layout and the post-implementation **shipping** layout. The "shipping" layout is what the code produces today.

### Before (pre-Phase 1)

```
<cache-dir>/
├── cache/
│   └── 1.21.11/           # VERSIONED ✅
│       └── client/        # Decompiled sources
└── index/
    ├── manifest.json      # GLOBAL ❌ (single version only)
    ├── minecraft/         # GLOBAL ❌
    │   └── net.minecraft.client.json
    └── fabric/            # GLOBAL ❌
        └── ...
```

### After (shipped)

```
<cache-dir>/
├── cache/
│   ├── 1.21.1/
│   │   └── client/
│   ├── 1.20.4/
│   │   └── client/
│   └── 1.19.4/
│       └── client/
└── index/
    ├── 1.21.1/                    # VERSIONED ✅
    │   ├── manifest.json
    │   ├── minecraft/
    │   │   └── net.minecraft.client.json
    │   └── fabric/
    ├── 1.20.4/                    # VERSIONED ✅
    │   ├── manifest.json
    │   ├── minecraft/
    │   └── fabric/
    └── 1.19.4/                    # VERSIONED ✅
        ├── manifest.json
        ├── minecraft/
        └── fabric/
```

---

## Implementation TODOs

### Phase 1: Version-Aware Paths ✅ COMPLETE

**File: `src/utils/paths.ts`**

- [x] Add `getVersionedIndexDir(version: string): string`
- [x] Add `getVersionedIndexManifestPath(version: string): string`
- [x] Add `getVersionedMinecraftIndexPath(version: string): string`
- [x] Add `getVersionedFabricIndexPath(version: string): string`
- [x] Add `getVersionedPackageIndexPath(namespace, packageName, version): string`
- [x] Add `isVersionIndexed(version: string): boolean` - check if version has index manifest
- [x] Keep existing functions for backward compatibility during migration
- [x] Add `getIndexedVersions(): string[]` - list all indexed versions
- [x] Add `ensureVersionedIndexDirs(version: string): void` - create versioned index directories

### Phase 2: Version Manager ✅ COMPLETE

**File: `src/version-manager.ts` (NEW)**

- [x] Create `VersionManager` class with:
  - [x] `private activeVersion: string | null = null`
  - [x] `setVersion(version: string): void`
  - [x] `getVersion(): string | null`
  - [x] `requireVersion(): string` - throws if not set
  - [x] `isVersionSet(): boolean`
  - [x] `clearVersion(): void`
- [x] Export singleton `versionManager`
- [x] Add `isActiveVersionIndexed(): boolean` helper

### Phase 3: Update SourceStore ✅ COMPLETE

**File: `src/storage/source-store.ts`**

- [x] Add `private version: string | null = null`
- [x] Add `setVersion(version: string): void` - sets version and clears manifest cache
- [x] Add `getVersion(): string | null`
- [x] Update `isReady()` to check both version set AND index exists
- [x] Update `getManifest()` to use versioned path based on `this.version`
- [x] Update `getPackage()` to use versioned path (reads directly from path, not via indexer)
- [x] Update `resolveSourcePath()` to use `this.version` instead of manifest version
- [x] Keep singleton export — `src/storage/index.ts` exports both the `SourceStore` class and the `sourceStore` singleton. Tools call `sourceStore.setVersion(...)` after `versionManager.setVersion(...)`.

### Phase 4: Update Indexer ✅ COMPLETE

**File: `src/indexer/index.ts`**

- [x] Update `buildIndex()` to write to versioned paths using `minecraftVersion`
- [x] Update `loadIndexManifest(version?: string)` to accept optional version parameter
- [x] Update `loadPackageIndex(namespace, packageName, version?: string)` to accept version
- [x] Use versioned path functions instead of global paths
- [x] Updated `writePackageIndices()` to accept version parameter

### Phase 5: CLI Updates - Merge Callgraph into Init ✅ COMPLETE

**File: `src/cli.ts`**

- [x] Update `init` command to include callgraph generation:
  - Added `--skip-callgraph` option for users who don't need it
  - Calls `ensureCallgraph()` after `buildIndex()` completes
  - Updated description to reflect full initialization
  - Made `-v` required (removed default version)
- [x] Keep `callgraph` command for regenerating callgraph only (useful for debugging)
- [x] Update `status` command to show per-version status with callgraph info
- [x] Update `rebuild` command with `--with-callgraph` option
- [x] Remove `DEFAULT_MC_VERSION` constant (require explicit version)
- [x] Update `clean` command with `-v <version>` option for version-specific cleaning

### Phase 6: New MCP Tools ✅ COMPLETE (unified into `mc_version`)

**File: `src/tools/static/version.ts`**

The original plan called for two separate tools (`mc_set_version`, `mc_list_versions`). During implementation these were unified into a single `mc_version` tool with an `action: 'set' | 'list'` parameter to keep the surface compact. Both behaviours are preserved:

- [x] `mc_version` with `action: 'set'`:
  - Checks if version is decompiled (via `getMinecraftSourceDir(...)` existence) and indexed (via `isVersionIndexed(...)`)
  - Returns "STOP and ask the USER to run …" instructions when not initialized
  - Calls `versionManager.setVersion(...)` and `sourceStore.setVersion(...)`
  - Returns success line including callgraph status (`hasCallgraphDb(...)`)

- [x] `mc_version` with `action: 'list'`:
  - Lists versions found by `getAvailableMinecraftVersions()`
  - Cross-references against `getIndexedVersions()` and `hasCallgraphDb(version)`
  - Reports decompiled/indexed/callgraph state per version and shows the active version when set

### Phase 7: Update Existing Tools ✅ COMPLETE

**Files: `src/tools/static/*.ts`**

For each existing tool, updated handler pattern:

- [x] Update `mc_search` tool:
  - Added optional `version` parameter to inputSchema
  - Uses `getEffectiveVersion()` helper for version resolution

- [x] Update `mc_get_class` tool (same pattern)
- [x] Update `mc_get_method` tool (same pattern)
- [x] Update `mc_list_classes` tool (same pattern)
- [x] Update `mc_list_packages` tool (same pattern)
- [x] Update `mc_find_hierarchy` tool (same pattern)
- [x] Update `mc_find_refs` tool (same pattern)

**Helper function added:**

```typescript
// src/tools/static/helpers.ts
export function getEffectiveVersion(explicitVersion?: string): { version: string; error?: string };
```

- Removed `DEFAULT_MC_VERSION` constant
- Removed old `ensureInitialized()` function
- All tools now resolve a version via `mc_version({action:"set"})` or an explicit `version` parameter; otherwise the helper returns a "STOP and ask the USER" error.

### Phase 8: Update Initialization Logic ✅ COMPLETE

**Files: `src/tools/static/*.ts`, `src/tools/static/helpers.ts`**

- [x] Remove `DEFAULT_MC_VERSION` constant
- [x] Remove old `ensureInitialized()` function entirely
- [x] All version checking now handled by `getEffectiveVersion()` helper

### Phase 9: Update Callgraph Functions ✅ COMPLETE

**File: `src/callgraph/query.ts`**

- [x] `findCallers(version, className, methodName)` - already has version param ✅
- [x] `findCallees(version, className, methodName)` - already has version param ✅
- [x] `mc_find_refs` tool passes version from `getEffectiveVersion()`

**File: `src/callgraph/index.ts`**

- [x] `hasCallgraphDb(version)` - already has version param ✅

### Phase 10: Migration Support — ABANDONED

This phase was scoped before Phases 1–9 shipped and is no longer needed:

- The old global index format only ever existed on developer machines that pre-dated Phase 1; there are no end-user installs to migrate.
- Users on a fresh install run `mcdev-mcp init -v <version>` per version, which writes directly to the versioned layout.
- Anyone who somehow has a stale global index can simply delete `<cache-dir>/index/manifest.json` (and the sibling `minecraft/`, `fabric/` dirs) and re-run `init`.

`src/utils/migration.ts` is intentionally **not** present in the repository. The `src/index.ts` startup path does not call any migration routine. If a future schema change requires migration, this section should be re-opened with a fresh design.

---

## API Reference (Current Shipped Surface)

### `mc_version`

A single tool with an `action` parameter handles both setting the active version and listing available versions. Implemented in `src/tools/static/version.ts`.

**Parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `action` | `"set" \| "list"` | Yes | Operation to perform |
| `version` | string | When `action="set"` | Minecraft version (e.g., `"1.21.1"`) |

**`action: "set"`**

Sets the active version on both `versionManager` and `sourceStore`. Verifies the version is decompiled (`getMinecraftSourceDir(...)` exists) and indexed (`isVersionIndexed(...)`). On failure, returns a "STOP and ask the USER to run …" message with the exact CLI command.

```
User: Set version to 1.21.1
→ mc_version({ action: "set", version: "1.21.1" })
← Active version set to 1.21.1.
  Indexed: yes
  Callgraph: yes
```

**`action: "list"`**

Returns one line per cached version with decompiled/indexed/callgraph status, and shows the active version (or a hint that none is set).

```
→ mc_version({ action: "list" })
← Available Minecraft versions:
  1.21.1: decompiled, indexed, callgraph
  1.20.4: decompiled, indexed, no callgraph
  1.19.4: decompiled, not indexed, no callgraph

  Active version: 1.21.1
```

### Updated Tools (All Now Support Optional `version` Parameter)

All existing tools now accept an optional `version` parameter:

| Tool | New Parameter |
|------|---------------|
| mc_search | `version?: string` |
| mc_get_class | `version?: string` |
| mc_get_method | `version?: string` |
| mc_list_classes | `version?: string` |
| mc_list_packages | `version?: string` |
| mc_find_hierarchy | `version?: string` |
| mc_find_refs | `version?: string` |

**Behavior:**
- If `version` provided → use that version (must be initialized)
- If `version` not provided → use the active version set via `mc_version({action:"set"})`
- If neither available → return error telling AI to STOP

---

## Testing Checklist

The behaviours below were validated during the original implementation and are still the contract today:

- [x] `mc_version` with `action: "list"` returns the cached versions with callgraph status
- [x] `mc_version` with `action: "set"` succeeds for a fully initialized version
- [x] `mc_version` with `action: "set"` fails with a helpful error for a non-initialized version
- [x] All tools fail with an error when no version is set and no `version` parameter is passed
- [x] All tools work after `mc_version({action:"set"})`
- [x] All tools accept an optional `version` parameter to override the active version
- [x] Switching between versions works correctly
- [x] CLI `init` command includes callgraph generation by default
- [x] CLI `init --skip-callgraph` skips callgraph generation
- [x] CLI `callgraph` command still works for regeneration
- [x] CLI `status` shows callgraph status per-version
- [x] CLI `rebuild` optionally rebuilds callgraph (`--with-callgraph`)
- [N/A] Old global index migration — see Phase 10 above; deliberately not implemented

---

## File Change Summary

| File | Action | Changes |
|------|--------|---------|
| `src/utils/paths.ts` | Modify | Add versioned index path functions |
| `src/version-manager.ts` | **Create** | Version state management |
| `src/storage/source-store.ts` | Modify | Make version-aware (still exposed via `sourceStore` singleton) |
| `src/storage/index.ts` | Minor | Export both the `SourceStore` class and the `sourceStore` singleton |
| `src/tools/static/version.ts` | **Create** | Unified `mc_version` tool (action: set / list) |
| `src/tools/static/helpers.ts` | **Create** | `getEffectiveVersion()` helper used by every static tool |
| `src/tools/static/*.ts` | Modify | All static tools accept optional `version` parameter |
| `src/indexer/index.ts` | Modify | Use versioned paths |
| `src/callgraph/query.ts` | Minor | Ensure version param passed correctly |
| `src/cli.ts` | Modify | Merge callgraph into init, add --skip-callgraph and --with-callgraph |

> Phase 10 originally proposed `src/utils/migration.ts` and a startup migration call in `src/index.ts`; both were dropped (see Phase 10 above).

---

## Error Messages

Standard error format for uninitialized versions:

```
Version {version} not initialized.

STOP and ask the USER to run this command in their terminal:
  npx mcdev-mcp init -v {version}

This will download, decompile, and index Minecraft {version} sources.
```

Standard error for version without callgraph (when using `mc_find_refs`):

```
Version {version} does not have callgraph data.

STOP and ask the USER to run this command in their terminal:
  npx mcdev-mcp callgraph -v {version}

Or for full reinitialization:
  npx mcdev-mcp init -v {version}
```

Standard error for no version set:

```
No Minecraft version is currently set.

STOP and ask the USER which version they want to use, then call mc_version with action="set".
Or, provide a 'version' parameter in your tool call.

To see available versions, use mc_version with action="list".
```
