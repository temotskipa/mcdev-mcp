import * as fs from 'fs';
import * as path from 'path';
import { ensureVineflower, type ProgressCallback } from './tools.js';
import { downloadClientJar, fetchVersionInfo, downloadMappings } from './download.js';
import { decompile } from './vineflower.js';
import {
  needsRemapping,
  ensureTinyRemapper,
  convertProguardToTiny,
  remapJar,
  getMappingsPath,
  getTinyMappingsPath,
} from './remapper.js';
import {
  getMinecraftSourceDir,
  getMinecraftJarPath,
  getObfuscatedJarPath,
  ensureHomeDirs,
  ensureDir,
} from '../utils/paths.js';

export function isDecompiled(version: string): boolean {
  const sourceDir = getMinecraftSourceDir(version);
  if (!fs.existsSync(sourceDir)) return false;
  const files = fs.readdirSync(sourceDir, { recursive: true }) as string[];
  const javaFiles = files.filter(f => f.endsWith('.java'));
  return javaFiles.length > 100;
}

/**
 * Sanity-check that a cached jar isn't a partial download. We don't validate
 * the full archive (that's expensive and the OS/disk corruption case is rare),
 * but a zero-byte file or one missing the `PK\x03\x04` zip magic is a clear
 * tombstone — almost always the result of an interrupted download. Treat it
 * as "not present" so the caller re-downloads.
 */
function isJarHealthy(jarPath: string): boolean {
  try {
    const stat = fs.statSync(jarPath);
    if (stat.size < 4) return false;
    const fd = fs.openSync(jarPath, 'r');
    try {
      const header = Buffer.alloc(4);
      fs.readSync(fd, header, 0, 4, 0);
      // Zip local file header (`PK\x03\x04`) or empty archive (`PK\x05\x06`).
      // Anything else means the file isn't a valid jar.
      return (
        header[0] === 0x50 && header[1] === 0x4b &&
        ((header[2] === 0x03 && header[3] === 0x04) ||
         (header[2] === 0x05 && header[3] === 0x06))
      );
    } finally {
      fs.closeSync(fd);
    }
  } catch {
    return false;
  }
}

export async function ensureDecompiled(
  version: string,
  progressCb?: ProgressCallback
): Promise<{ minecraftDir: string }> {
  ensureHomeDirs();

  const minecraftDir = getMinecraftSourceDir(version);

  if (!isDecompiled(version)) {
    const vineflowerJar = await ensureVineflower(progressCb);
    const finalJarPath = getMinecraftJarPath(version);
    ensureDir(path.dirname(finalJarPath));

    if (needsRemapping(version)) {
      // Obfuscated version: download, remap, then decompile
      const obfuscatedJarPath = getObfuscatedJarPath(version);

      // Treat a corrupt cached jar as missing — a partial download from a
      // previous interrupted run will silently fail decompilation otherwise.
      if (fs.existsSync(finalJarPath) && !isJarHealthy(finalJarPath)) {
        if (progressCb) progressCb('download', 0, 'Cached client jar looks corrupt, re-downloading...');
        try { fs.unlinkSync(finalJarPath); } catch {}
      }

      if (!fs.existsSync(finalJarPath)) {
        await downloadClientJar(version, obfuscatedJarPath, progressCb);

        const versionInfo = await fetchVersionInfo(version);
        const mappingsPath = getMappingsPath(version);
        await downloadMappings(versionInfo, path.dirname(mappingsPath), progressCb);

        await ensureTinyRemapper(progressCb);

        const tinyPath = getTinyMappingsPath(version);
        if (progressCb) progressCb('convert', 0, 'Converting ProGuard mappings to Tiny format...');
        convertProguardToTiny(mappingsPath, tinyPath);
        if (progressCb) progressCb('convert', 100, 'Mappings converted.');

        await remapJar(obfuscatedJarPath, tinyPath, finalJarPath, progressCb);
      }

      await decompile(vineflowerJar, finalJarPath, minecraftDir, progressCb);
    } else {
      // Unobfuscated version: download directly and decompile
      if (fs.existsSync(finalJarPath) && !isJarHealthy(finalJarPath)) {
        if (progressCb) progressCb('download', 0, 'Cached client jar looks corrupt, re-downloading...');
        try { fs.unlinkSync(finalJarPath); } catch {}
      }
      await downloadClientJar(version, finalJarPath, progressCb);
      await decompile(vineflowerJar, finalJarPath, minecraftDir, progressCb);
    }
  }

  return { minecraftDir };
}
