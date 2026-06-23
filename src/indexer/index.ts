import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { fork } from 'child_process';
import { fileURLToPath } from 'url';
import { glob } from 'glob';
import { PackageIndex, IndexManifest, ClassInfo } from '../utils/types.js';
import type { ParsedClass } from './parser.js';
import { parseJavaFile, getParserBackend } from './parser.js';
import {
  getVersionedIndexManifestPath,
  getVersionedPackageIndexPath,
  ensureVersionedIndexDirs
} from '../utils/paths.js';
import { readJsonFileOrNull } from '../utils/json-file.js';

const AST_WORKER_BATCH_SIZE = 10;
const AST_WORKER_HEAP_MB = 2048;
const AST_WORKER_RETRY_HEAP_MB = 8192;
const WORKER_STDERR_LIMIT = 8000;

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
  let nextProgressAt = Math.floor(processedOffset / 500) * 500 + 500;
  let activePackage: string | null = null;
  let activeClasses: Record<string, ClassInfo> | null = null;

  async function indexParsedClass(parsed: ParsedClass): Promise<void> {
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

  function reportProcessed(count: number): void {
    processedFiles += count;
    if (progressCb && totalFiles > 0 && processedFiles >= nextProgressAt) {
      const progress = Math.round(5 + (processedFiles / totalFiles) * 85);
      progressCb('index', progress, `Processed ${processedFiles}/${totalFiles} files...`);
      while (processedFiles >= nextProgressAt) nextProgressAt += 500;
    }
  }

  if (shouldUseAstWorkers(sortedFiles.length)) {
    await parseJavaFilesInWorkerBatches(sortedFiles, async batch => {
      for (const parsed of batch.parsed) {
        await indexParsedClass(parsed);
      }
    }, reportProcessed);
  } else {
    for (const file of sortedFiles) {
      const parsed = parseJavaFile(file);
      if (parsed) {
        await indexParsedClass(parsed);
      }

      reportProcessed(1);
      if (processedFiles % 100 === 0) {
        await yieldForGc();
      }
    }
  }

  if (activePackage && activeClasses && Object.keys(activeClasses).length > 0) {
    await flushPackage(namespace, activePackage, activeClasses, version, packageNames);
  }

  return processedFiles;
}

interface ParsedBatch {
  fileCount: number;
  parsed: ParsedClass[];
}

function shouldUseAstWorkers(fileCount: number): boolean {
  if (fileCount === 0 || getParserBackend() !== 'ast') return false;
  if (getAstWorkerCount() < 1) return false;
  return fs.existsSync(getParseWorkerPath());
}

async function parseJavaFilesInWorkerBatches(
  sortedFiles: string[],
  onBatchParsed: (batch: ParsedBatch) => Promise<void>,
  onScheduledBatchComplete?: (fileCount: number) => void,
): Promise<void> {
  const batchSize = getAstWorkerBatchSize();
  const batches: string[][] = [];
  for (let i = 0; i < sortedFiles.length; i += batchSize) {
    batches.push(sortedFiles.slice(i, i + batchSize));
  }

  const completedBatches = new Map<number, ParsedBatch>();
  const workerCount = Math.min(getAstWorkerCount(), batches.length);
  let nextBatch = 0;
  let nextBatchToIndex = 0;
  let consumeTail = Promise.resolve();

  function consumeReadyBatches(): Promise<void> {
    consumeTail = consumeTail.then(async () => {
      while (completedBatches.has(nextBatchToIndex)) {
        const batch = completedBatches.get(nextBatchToIndex)!;
        completedBatches.delete(nextBatchToIndex);
        await onBatchParsed(batch);
        nextBatchToIndex++;
        await yieldForGc();
      }
    });
    return consumeTail;
  }

  async function runNextBatch(): Promise<void> {
    while (nextBatch < batches.length) {
      const batchIndex = nextBatch++;
      const files = batches[batchIndex];
      const parsed = await parseBatchWithRetry(files);
      completedBatches.set(batchIndex, { fileCount: files.length, parsed });
      if (onScheduledBatchComplete) onScheduledBatchComplete(files.length);
      await consumeReadyBatches();
    }
  }

  await Promise.all(Array.from({ length: workerCount }, () => runNextBatch()));
  await consumeReadyBatches();
}

async function parseBatchWithRetry(files: string[]): Promise<ParsedClass[]> {
  try {
    return await parseBatchInWorker(files);
  } catch (error) {
    if (files.length <= 1) {
      const retryHeapMb = getAstWorkerRetryHeapMb();
      if (retryHeapMb > getAstWorkerHeapMb()) {
        try {
          return await parseBatchInWorker(files, retryHeapMb);
        } catch {}
      }

      const cause = error instanceof Error ? error.message : String(error);
      throw new Error(`Java parse worker failed for ${files[0]}: ${cause}`);
    }

    const midpoint = Math.floor(files.length / 2);
    const left = await parseBatchWithRetry(files.slice(0, midpoint));
    const right = await parseBatchWithRetry(files.slice(midpoint));
    return [...left, ...right];
  }
}

function parseBatchInWorker(files: string[], heapMb = getAstWorkerHeapMb()): Promise<ParsedClass[]> {
  const workerPath = getParseWorkerPath();

  return new Promise((resolve, reject) => {
    let settled = false;
    let stderr = '';
    const child = fork(workerPath, [], {
      env: { ...process.env, MCDEV_AST_PARSER: '1' },
      execArgv: [
        ...getWorkerExecArgv(),
        `--max-old-space-size=${heapMb}`,
      ],
      stdio: ['ignore', 'ignore', 'pipe', 'ipc'],
    });

    child.stderr?.on('data', (chunk: Buffer) => {
      stderr += chunk.toString();
      if (stderr.length > WORKER_STDERR_LIMIT) {
        stderr = stderr.slice(-WORKER_STDERR_LIMIT);
      }
    });

    child.on('message', (message: unknown) => {
      const msg = message as { type?: string; parsed?: ParsedClass[]; error?: string };
      if (msg.type === 'result' && Array.isArray(msg.parsed)) {
        settled = true;
        resolve(msg.parsed);
        return;
      }
      if (msg.type === 'error') {
        settled = true;
        reject(new Error(msg.error || 'Java parse worker failed.'));
      }
    });

    child.on('error', error => {
      if (settled) return;
      settled = true;
      reject(error);
    });

    child.on('exit', (code, signal) => {
      if (settled) return;
      settled = true;
      const detail = stderr.trim() ? `\n${stderr.trim()}` : '';
      reject(new Error(`Java parse worker exited with ${signal ?? `code ${code}`}.${detail}`));
    });

    child.send({ type: 'parse', files });
  });
}

function getParseWorkerPath(): string {
  return fileURLToPath(new URL('./parse-worker.js', import.meta.url));
}

function getWorkerExecArgv(): string[] {
  const args: string[] = [];
  for (let i = 0; i < process.execArgv.length; i++) {
    const arg = process.execArgv[i];
    if (
      arg.startsWith('--max-old-space-size=') ||
      arg.startsWith('--input-type=') ||
      arg === '--input-type' ||
      arg === '--eval' ||
      arg === '-e' ||
      arg === '--print' ||
      arg === '-p'
    ) {
      if (arg === '--input-type' || arg === '--eval' || arg === '-e' || arg === '--print' || arg === '-p') i++;
      continue;
    }
    args.push(arg);
  }
  return args;
}

function getAstWorkerCount(): number {
  const cpuCount = typeof os.availableParallelism === 'function' ? os.availableParallelism() : os.cpus().length;
  const defaultWorkers = Math.max(1, Math.min(2, cpuCount - 1));
  return readIntEnv('MCDEV_INDEX_WORKERS', defaultWorkers, 0, 32);
}

function getAstWorkerBatchSize(): number {
  return readIntEnv('MCDEV_INDEX_BATCH_SIZE', AST_WORKER_BATCH_SIZE, 1, 2000);
}

function getAstWorkerHeapMb(): number {
  return readIntEnv('MCDEV_INDEX_WORKER_HEAP_MB', AST_WORKER_HEAP_MB, 128, 32768);
}

function getAstWorkerRetryHeapMb(): number {
  return readIntEnv('MCDEV_INDEX_WORKER_RETRY_HEAP_MB', AST_WORKER_RETRY_HEAP_MB, 128, 32768);
}

function readIntEnv(name: string, fallback: number, min: number, max: number): number {
  const raw = process.env[name];
  if (!raw) return fallback;
  const parsed = Number.parseInt(raw, 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
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
