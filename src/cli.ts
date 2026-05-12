#!/usr/bin/env node

// Debug helper: opt-in lifecycle logging to a file on disk. See the matching
// block in src/mcpb-bootstrap.ts for the full rationale — the short version
// is that stderr under the Claude Desktop MCPB host can raise async EPIPE
// and kill the process, so all debug logging goes to a file instead.
//
// MCDEV_MCP_DEBUG_LOG:
//   unset / empty / "off" → disabled (default)
//   "on"                  → /tmp/mcdev-debug.log
//   any other value       → used as the log file path
import * as fsForDebug from 'fs';

const DEBUG_LOG_PATH_CLI = (() => {
  const override = process.env.MCDEV_MCP_DEBUG_LOG;
  if (!override || override === 'off') return null;
  if (override === 'on') return '/tmp/mcdev-debug.log';
  return override;
})();

function bootLog(msg: string): void {
  if (!DEBUG_LOG_PATH_CLI) return;
  // File-only on purpose: writing to stderr under the Claude Desktop MCPB
  // host raises async EPIPE and crashes us. See src/mcpb-bootstrap.ts.
  const stamped = `[${new Date().toISOString()}] [mcdev-mcp cli] ${msg}`;
  try { fsForDebug.appendFileSync(DEBUG_LOG_PATH_CLI, stamped + '\n'); } catch {}
}

import * as fs from 'fs';
import * as path from 'path';
import { Command } from 'commander';
import { ensureDecompiled } from './decompiler/index.js';
import { buildIndex, loadIndexManifest } from './indexer/index.js';
import {
  getMinecraftSourceDir,
  ensureHomeDirs,
  getHomeDir,
  getAvailableMinecraftVersions,
  getCacheDir,
  getIndexDir,
  getMinecraftCacheDir,
  getIndexedVersions,
  isVersionIndexed,
  getTmpDir
} from './utils/paths.js';
import { ensureCallgraph, hasCallgraphDb, getCallgraphStats } from './callgraph/index.js';
import { startServer } from './index.js';
import { getPackageVersion } from './utils/pkg-version.js';

// Shared progress callback. The `init` / `callgraph` / `rebuild` actions
// all need to forward progress messages to stdout in the same shape
// (`[stage] N% - message`); centralising this avoids three near-identical
// inline lambdas drifting apart.
const cliProgressCb = (stage: string, progress: number, message: string): void => {
  console.log(`[${stage}] ${progress}% - ${message}`);
};

bootLog('module loaded — all top-level imports resolved');

const program = new Command();

function isValidVersion(version: string): boolean {
  // Allow 26.x, 27.x, etc. (including snapshots like 26.1-snapshot-10)
  if (/^[2-9][0-9]*\./.test(version)) {
    return true;
  }

  // For 1.x.x versions (e.g. 1.19.4), require >= 1.14
  const match3 = version.match(/^1\.(\d+)\.(\d+)/);
  if (match3) {
    const minor = parseInt(match3[1], 10);
    return minor >= 14;
  }

  // For 1.x versions without patch (e.g. 1.19), require >= 1.14
  const match2 = version.match(/^1\.(\d+)$/);
  if (match2) {
    const minor = parseInt(match2[1], 10);
    return minor >= 14;
  }

  return false;
}

function validateVersion(version: string): void {
  if (!isValidVersion(version)) {
    console.error(`Error: Version ${version} is not supported.`);
    console.error('Supported versions:');
    console.error('  - 1.14 and later (official Mojang mappings required)');
    console.error('  - 26.x and later (26.1, 26.1-snapshot-10, etc.)');
    process.exit(1);
  }
}

program
  .name('mcdev-mcp')
  .description('MCP server for Minecraft mod development')
  .version(getPackageVersion());

program
  .command('serve')
  .description('Start the MCP server over stdio (used by MCP clients, not humans)')
  .action(async () => {
    bootLog('serve action entered — calling startServer()');
    try {
      await startServer();
      bootLog('startServer() resolved — waiting for stdio messages');
    } catch (error) {
      const msg = error instanceof Error ? (error.stack ?? error.message) : String(error);
      try { process.stderr.write(`[mcdev-mcp serve fatal] ${msg}\n`); } catch {}
      process.exit(1);
    }
  });

program
  .command('init')
  .description('Download, decompile, index Minecraft sources, and generate callgraph')
  .requiredOption('-v, --version <version>', 'Minecraft version (e.g., 1.21.11, 26.1)')
  .option('--skip-callgraph', 'Skip callgraph generation', false)
  .action(async (options) => {
    validateVersion(options.version);
    console.log(`Initializing mcdev-mcp for Minecraft ${options.version}...`);
    
    try {
      const result = await ensureDecompiled(options.version, cliProgressCb);

      console.log('\nBuilding symbol index...');
      await buildIndex({
        minecraftSourceDir: result.minecraftDir,
        fabricApiSourceDir: null,
        minecraftVersion: options.version,
        fabricApiVersion: null,
        progressCb: cliProgressCb,
      });

      if (!options.skipCallgraph) {
        console.log('\nGenerating callgraph...');
        await ensureCallgraph(options.version, cliProgressCb);
      }
      
      console.log('\n✓ Initialization complete!');
      console.log(`  Minecraft: ${options.version}`);
      if (options.skipCallgraph) {
        console.log(`  Callgraph: skipped (run 'mcdev-mcp callgraph -v ${options.version}' to generate)`);
      } else {
        const stats = await getCallgraphStats(options.version);
        if (stats) {
          console.log(`  Callgraph: ${stats.totalCalls} call references`);
        }
      }
    } catch (error) {
      console.error('Initialization failed:', error);
      process.exit(1);
    }
  });

program
  .command('callgraph')
  .description('Generate callgraph database for finding method references')
  .requiredOption('-v, --version <version>', 'Minecraft version (e.g., 1.21.11, 26.1)')
  .action(async (options) => {
    validateVersion(options.version);
    console.log(`Generating callgraph for Minecraft ${options.version}...`);
    
    const sourceDir = getMinecraftSourceDir(options.version);
    if (!fs.existsSync(sourceDir)) {
      console.error(`Minecraft ${options.version} not decompiled. Run 'init -v ${options.version}' first.`);
      process.exit(1);
    }
    
    if (!isVersionIndexed(options.version)) {
      console.error(`Minecraft ${options.version} not indexed. Run 'init -v ${options.version}' first.`);
      process.exit(1);
    }

    try {
      await ensureCallgraph(options.version, cliProgressCb);

      const stats = await getCallgraphStats(options.version);
      if (stats) {
        console.log('\n✓ Callgraph database ready!');
        console.log(`  Total call references: ${stats.totalCalls}`);
        console.log(`  Unique callers: ${stats.uniqueCallers}`);
        console.log(`  Unique callees: ${stats.uniqueCallees}`);
      }
    } catch (error) {
      console.error('Callgraph generation failed:', error);
      process.exit(1);
    }
  });

program
  .command('rebuild')
  .description('Rebuild the symbol index from cached sources')
  .requiredOption('-v, --version <version>', 'Minecraft version (e.g., 1.21.11, 26.1)')
  .option('--with-callgraph', 'Also rebuild callgraph', false)
  .action(async (options) => {
    validateVersion(options.version);
    ensureHomeDirs();
    
    const minecraftVersion = options.version;
    const minecraftDir = getMinecraftSourceDir(minecraftVersion);
    
    if (!fs.existsSync(minecraftDir)) {
      console.error(`Source directory not found: ${minecraftDir}`);
      console.error('Run `init` first to download and decompile sources.');
      process.exit(1);
    }
    
    console.log(`Rebuilding index for Minecraft ${minecraftVersion}...`);

    await buildIndex({
      minecraftSourceDir: minecraftDir,
      fabricApiSourceDir: null,
      minecraftVersion: minecraftVersion,
      fabricApiVersion: null,
      progressCb: cliProgressCb,
    });

    if (options.withCallgraph) {
      console.log('\nRebuilding callgraph...');
      await ensureCallgraph(minecraftVersion, cliProgressCb);
    }
    
    console.log('\n✓ Index rebuilt!');
  });

program
  .command('status')
  .description('Show current status of all cached Minecraft versions')
  .option('-v, --version <version>', 'Show status for specific version')
  .action(async (options) => {
    ensureHomeDirs();

    const indexedVersions = getIndexedVersions();
    const cachedVersions = getAvailableMinecraftVersions();

    if (options.version) {
      await showVersionStatus(options.version, cachedVersions, indexedVersions);
      return;
    }

    if (cachedVersions.length === 0) {
      console.log('Status: Not initialized');
      console.log('Run `mcdev-mcp init -v <version>` to set up.');
      return;
    }

    console.log('Cached Minecraft versions:\n');

    for (const version of cachedVersions) {
      const manifest = loadIndexManifest(version);
      const hasCallgraph = hasCallgraphDb(version);
      const isIndexed = indexedVersions.includes(version);

      console.log(`  ${version}:`);
      console.log(`    Decompiled: ✓`);
      console.log(`    Indexed: ${isIndexed ? '✓' : '✗'}`);

      if (isIndexed && manifest) {
        console.log(`    Packages: ${manifest.packages.minecraft.length} Minecraft, ${manifest.packages.fabric.length} Fabric`);
      }

      console.log(`    Callgraph: ${hasCallgraph ? '✓' : '✗'}`);

      if (hasCallgraph) {
        const stats = await getCallgraphStats(version);
        if (stats) {
          console.log(`    Call refs: ${stats.totalCalls}`);
        }
      }

      console.log();
    }

    console.log(`Total: ${cachedVersions.length} version(s) cached`);
  });

async function showVersionStatus(version: string, cachedVersions: string[], indexedVersions: string[]): Promise<void> {
  const isCached = cachedVersions.includes(version);
  const isIndexed = indexedVersions.includes(version);
  const hasCallgraph = hasCallgraphDb(version);

  console.log(`\nMinecraft ${version}:`);
  console.log(`  Decompiled: ${isCached ? '✓' : '✗'}`);
  console.log(`  Indexed: ${isIndexed ? '✓' : '✗'}`);
  console.log(`  Callgraph: ${hasCallgraph ? '✓' : '✗'}`);

  if (isIndexed) {
    const manifest = loadIndexManifest(version);
    if (manifest) {
      console.log(`  Packages: ${manifest.packages.minecraft.length} Minecraft, ${manifest.packages.fabric.length} Fabric`);
      console.log(`  Generated: ${manifest.generated}`);
    }

    if (hasCallgraph) {
      const stats = await getCallgraphStats(version);
      if (stats) {
        console.log(`  Call refs: ${stats.totalCalls}`);
        console.log(`  Unique callers: ${stats.uniqueCallers}`);
        console.log(`  Unique callees: ${stats.uniqueCallees}`);
      }
    }
  } else if (!isCached) {
    console.log(`\n  Run 'mcdev-mcp init -v ${version}' to initialize.`);
  }
}

program
  .command('clean')
  .description('Remove cached data and index')
  .option('--callgraph', 'Only clean callgraph data')
  .option('--cache', 'Clean cache directory (decompiled sources)')
  .option('--index', 'Clean index directory (symbol index)')
  .option('--all', 'Clean everything (cache, index, DecompilerMC)')
  .option('-v, --version <version>', 'Clean data for specific version only')
  .action((options) => {
    // Every other CLI command wraps its body so an `fs.rmSync` permission
    // error or stale-handle EBUSY surfaces as a readable line instead of an
    // unhandled stack trace. `clean` was the only outlier; keep it consistent.
    const tryRm = (target: string, label: string): void => {
      try {
        if (fs.existsSync(target)) {
          fs.rmSync(target, { recursive: true });
          console.log(`Removed ${label}: ${target}`);
        } else {
          console.log(`${label} not found: ${target}`);
        }
      } catch (e) {
        const msg = e instanceof Error ? e.message : String(e);
        console.error(`Error removing ${label} at ${target}: ${msg}`);
        console.error('  (Hint: another process may have files open, or the path may be on read-only media.)');
        process.exitCode = 1;
      }
    };

    try {
      const homeDir = getHomeDir();
      const cacheDir = getCacheDir();
      const indexDir = getIndexDir();

      if (options.callgraph && !options.version) {
        console.error('--callgraph requires -v <version>');
        process.exit(1);
      }

      if (options.callgraph && options.version) {
        const callgraphDir = path.join(getMinecraftCacheDir(options.version), 'callgraph');
        tryRm(callgraphDir, `callgraph data for ${options.version}`);
        return;
      }

      if (options.version) {
        const versionCacheDir = path.join(cacheDir, options.version);
        const versionIndexDir = path.join(indexDir, options.version);

        if (options.all || (!options.cache && !options.index)) {
          options.cache = true;
          options.index = true;
        }

        if (options.cache) tryRm(versionCacheDir, `cache for ${options.version}`);
        if (options.index) tryRm(versionIndexDir, `index for ${options.version}`);

        console.log(`\nRun 'mcdev-mcp init -v ${options.version}' to reinitialize.`);
        return;
      }

      if (options.all) {
        options.cache = true;
        options.index = true;
      }

      if (!options.cache && !options.index) {
        console.log('Specify what to clean:');
        console.log('  --cache           Clean decompiled sources');
        console.log('  --index           Clean symbol index');
        console.log('  --callgraph       Clean callgraph database only (requires -v)');
        console.log('  --all             Clean everything (cache, index, tmp)');
        console.log('  -v <version>      Clean data for specific version only');
        return;
      }

      if (options.cache) tryRm(cacheDir, 'cache');
      if (options.index) tryRm(indexDir, 'index');

      if (options.all) {
        const tmpDir = path.join(homeDir, 'tmp');
        tryRm(tmpDir, 'tmp');
      }

      console.log('\nRun `mcdev-mcp init -v <version>` to reinitialize.');
    } catch (e) {
      const msg = e instanceof Error ? (e.stack ?? e.message) : String(e);
      console.error(`Unexpected error in 'clean': ${msg}`);
      process.exit(1);
    }
  });

program.parse();
