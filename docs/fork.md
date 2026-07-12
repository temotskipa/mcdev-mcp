# DecompilerMC Fork Plan — ABANDONED

> **Pinned Node oracle evidence:** Historical source paths below refer to the commit recorded in
> [`contracts/node-oracle.json`](../contracts/node-oracle.json), not files in the current Java worktree.

> **Status:** Not implemented. This document is retained as design history; the actual decompiler in mcdev-mcp is **Vineflower** (no Python, no DecompilerMC fork). Read [VF.md](VF.md) for the shipped flow.

## What this plan proposed

An earlier design considered forking [DecompilerMC](https://github.com/hube12/DecompilerMC) and maintaining a modified `lib/DecompilerMC-main.py` that:

1. Could decompile dev snapshots without Proguard mappings.
2. Took a `--lib-dir` argument so the CFR / FernFlower / SpecialSource jars could live outside the repo.

The plan called for `cloneDecompilerMC()`, `runDecompilerMC()`, and `hasDecompilerMCLibs()` helpers in `src/decompiler/index.ts`, plus an in-tree `lib/DecompilerMC-main.py`.

## Why it was abandoned

- **No Python dependency.** Vineflower is a single self-contained Java jar; mcdev-mcp can drive it directly via `java -jar` in `src/decompiler/vineflower.ts`.
- **Fewer moving parts.** Dropping DecompilerMC removed a clone step, a Python subprocess, and three bundled jars (CFR, FernFlower, SpecialSource).
- **Mappings handled separately.** Proguard remap is done in `src/decompiler/remapper.ts` using Tiny Remapper + the version's official Proguard mappings; dev snapshots without mappings can be decompiled directly from the unobfuscated jar.

None of `cloneDecompilerMC()`, `runDecompilerMC()`, `hasDecompilerMCLibs()`, or `lib/DecompilerMC-main.py` were ever committed. `lib/` is not part of the repository (the `.gitignore` entries that mention `lib/` are leftover and tracked separately for cleanup).

## Where the actual flow lives

| Concern | File |
|---|---|
| Top-level orchestration (`ensureDecompiled`, `getStatus`) | `src/decompiler/index.ts` |
| Vineflower driver | `src/decompiler/vineflower.ts` |
| Proguard → Tiny mapping conversion + remapping | `src/decompiler/remapper.ts` |
| Mojang manifest + jar download (with redirect handling) | `src/decompiler/download.ts` |
| Vineflower jar download | `src/decompiler/tools.ts` |
| Cache layout (versioned paths) | `src/utils/paths.ts` |

For the current end-to-end design, see [VF.md](VF.md).
