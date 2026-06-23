import * as fs from 'fs';
import * as path from 'path';
import { glob } from 'glob';
import { PackageIndex, IndexManifest, ClassInfo } from '../utils/types.js';
import { parseJavaFile, getParserBackend } from './parser.js';
import {
  getVersionedIndexManifestPath,
  getVersionedPackageIndexPath,
  ensureVersionedIndexDirs
} from '../utils/paths.js';
import { readJsonFileOrNull } from '../utils/json-file.js';

export interface BuildIndexOptions {
  minecraftSourceDir: string;
  fabricApiSourceDir?: string | null;
  minecraftVersion: string;
  fabricApiVersion?: string | null;
  progressCb?: (stage: string, progress: number, message: string) => void;
}

export interface IndexBuildResult {
  minecraftPackages: string[];
  fabricPackages: string[];
  totalClasses: number;
}

export async function buildIndex(options: BuildIndexOptions): Promise<IndexBuildResult> {
  const { minecraftSourceDir, fabricApiSourceDir, minecraftVersion, fabricApiVersion, progressCb } = options;
  
  ensureVersionedIndexDirs(minecraftVersion);
  
  if (progressCb) progressCb('index', 0, 'Finding Java files...');
  
  const mcJavaFiles = await findJavaFiles(minecraftSourceDir);
  const fabricJavaFiles = fabricApiSourceDir ? await findJavaFiles(fabricApiSourceDir) : [];
  
  const totalFiles = mcJavaFiles.length + fabricJavaFiles.length;
  let processedFiles = 0;
  let totalClasses = 0;
  
  const minecraftPackages = new Set<string>();
  const fabricPackages = new Set<string>();
  
  if (progressCb) progressCb('index', 5, `Processing ${mcJavaFiles.length} Minecraft files...`);
  
  processedFiles = await processJavaFiles({
    files: mcJavaFiles,
    namespace: 'minecraft',
    version: minecraftVersion,
    packageNames: minecraftPackages,
    totalFiles,
    processedOffset: processedFiles,
    onClassIndexed: () => { totalClasses++; },
    progressCb,
  });
  
  if (fabricJavaFiles.length > 0) {
    if (progressCb) progressCb('index', 50, `Processing ${fabricJavaFiles.length} Fabric API files...`);
  }

  processedFiles = await processJavaFiles({
    files: fabricJavaFiles,
    namespace: 'fabric',
    version: minecraftVersion,
    packageNames: fabricPackages,
    totalFiles,
    processedOffset: processedFiles,
    onClassIndexed: () => { totalClasses++; },
    progressCb,
  });
  
  if (progressCb) progressCb('index', 90, 'Writing package indices...');
  
  const manifest: IndexManifest = {
    minecraftVersion,
    fabricApiVersion: fabricApiVersion || null,
    generated: new Date().toISOString(),
    indexerVersion: getParserBackend(),
    packages: {
      minecraft: Array.from(minecraftPackages).sort(),
      fabric: Array.from(fabricPackages).sort(),
    },
  };
  
  const manifestPath = getVersionedIndexManifestPath(minecraftVersion);
  fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));
  
  if (progressCb) progressCb('index', 100, `Indexed ${totalClasses} classes in ${minecraftPackages.size + fabricPackages.size} packages.`);
  
  return {
    minecraftPackages: Array.from(minecraftPackages).sort(),
    fabricPackages: Array.from(fabricPackages).sort(),
    totalClasses,
  };
}

interface ProcessJavaFilesOptions {
  files: string[];
  namespace: 'minecraft' | 'fabric';
  version: string;
  packageNames: Set<string>;
  totalFiles: number;
  processedOffset: number;
  onClassIndexed: () => void;
  progressCb?: (stage: string, progress: number, message: string) => void;
}

async function processJavaFiles(options: ProcessJavaFilesOptions): Promise<number> {
  const {
    files,
    namespace,
    version,
    packageNames,
    totalFiles,
    processedOffset,
    onClassIndexed,
    progressCb,
  } = options;

  const sortedFiles = [...files].sort((a, b) => a.localeCompare(b));
  let processedFiles = processedOffset;
  let activePackage: string | null = null;
  let activeClasses: Record<string, ClassInfo> | null = null;

  for (const file of sortedFiles) {
    const parsed = parseJavaFile(file);
    if (parsed) {
      const packageName = parsed.packageName || 'default';

      if (activePackage !== null && packageName !== activePackage && activeClasses) {
        await flushPackage(namespace, activePackage, activeClasses, version, packageNames);
        activeClasses = null;
        await yieldForGc();
      }

      if (activePackage !== packageName) {
        activePackage = packageName;
        activeClasses = {};
      }

      activeClasses![parsed.className] = {
        ...parsed.info,
        sourcePath: parsed.info.sourcePath,
      };
      onClassIndexed();
    }

    processedFiles++;
    if (processedFiles % 100 === 0) {
      await yieldForGc();
    }
    if (progressCb && processedFiles % 500 === 0) {
      const progress = Math.round(5 + (processedFiles / totalFiles) * 85);
      progressCb('index', progress, `Processed ${processedFiles}/${totalFiles} files...`);
    }
  }

  if (activePackage && activeClasses && Object.keys(activeClasses).length > 0) {
    await flushPackage(namespace, activePackage, activeClasses, version, packageNames);
  }

  return processedFiles;
}

async function flushPackage(
  namespace: 'minecraft' | 'fabric',
  packageName: string,
  classes: Record<string, ClassInfo>,
  version: string,
  packageNames: Set<string>,
): Promise<void> {
  await writePackageIndex(namespace, packageName, classes, version);
  packageNames.add(packageName);
}

function yieldForGc(): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, 0));
}

async function findJavaFiles(dir: string): Promise<string[]> {
  if (!fs.existsSync(dir)) return [];
  
  return glob('**/*.java', {
    cwd: dir,
    absolute: true,
    nodir: true,
  });
}

async function writePackageIndex(
  namespace: 'minecraft' | 'fabric',
  packageName: string,
  classes: Record<string, ClassInfo>,
  version: string,
): Promise<void> {
  const packageIndex: PackageIndex = {
    package: packageName,
    classes,
  };

  const indexPath = getVersionedPackageIndexPath(namespace, packageName, version);
  const dir = path.dirname(indexPath);
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
  fs.writeFileSync(indexPath, JSON.stringify(packageIndex, null, 2));
}

export function loadIndexManifest(version?: string): IndexManifest | null {
  if (!version) return null;
  const manifestPath = getVersionedIndexManifestPath(version);
  return readJsonFileOrNull<IndexManifest>(manifestPath, `indexer/manifest:${version}`);
}

export function loadPackageIndex(
  namespace: 'minecraft' | 'fabric',
  packageName: string,
  version?: string
): PackageIndex | null {
  if (!version) return null;
  const indexPath = getVersionedPackageIndexPath(namespace, packageName, version);
  return readJsonFileOrNull<PackageIndex>(
    indexPath,
    `indexer/package:${version}/${namespace}/${packageName}`
  );
}