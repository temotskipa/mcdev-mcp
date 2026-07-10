import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { fork } from 'child_process';
import { fileURLToPath } from 'url';
import { glob } from 'glob';
import { PackageIndex, IndexManifest, ClassInfo } from '../utils/types.js';
import {
  parseJavaFileWithBackend,
  getParserBackend,
  type ParsedClass,
  type ParserBackend
} from './parser.js';
import { parseJavaFilesWithWorker, sourceFile } from './java-worker.js';
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

type LegacyWorkerBackend = ParserBackend | 'ast';

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
  const parserBackend = getParserBackend();
  let totalClasses = 0;

  const minecraftPackages = new Set<string>();
  const fabricPackages = new Set<string>();
  const writtenMinecraftPackages = new Set<string>();
  const writtenFabricPackages = new Set<string>();

  if (progressCb) progressCb('index', 5, `Processing ${mcJavaFiles.length} Minecraft files...`);

  const processedAfterMinecraft = await processJavaFiles({
    files: mcJavaFiles,
    namespace: 'minecraft',
    version: minecraftVersion,
    parserBackend,
    packageNames: minecraftPackages,
    writtenPackageNames: writtenMinecraftPackages,
    totalFiles,
    processedOffset: 0,
    onClassIndexed: () => { totalClasses++; },
    progressCb,
  });

  if (fabricJavaFiles.length > 0 && progressCb) {
    progressCb('index', 50, `Processing ${fabricJavaFiles.length} Fabric API files...`);
  }

  await processJavaFiles({
    files: fabricJavaFiles,
    namespace: 'fabric',
    version: minecraftVersion,
    parserBackend,
    packageNames: fabricPackages,
    writtenPackageNames: writtenFabricPackages,
    totalFiles,
    processedOffset: processedAfterMinecraft,
    onClassIndexed: () => { totalClasses++; },
    progressCb,
  });

  if (progressCb) progressCb('index', 90, 'Writing index manifest...');

  const manifest: IndexManifest = {
    minecraftVersion,
    fabricApiVersion: fabricApiVersion || null,
    generated: new Date().toISOString(),
    indexerVersion: parserBackend,
    packages: {
      minecraft: Array.from(minecraftPackages).sort(),
      fabric: Array.from(fabricPackages).sort(),
    },
  };

  const manifestPath = getVersionedIndexManifestPath(minecraftVersion);
  fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));

  if (progressCb) {
    progressCb('index', 100, `Indexed ${totalClasses} classes in ${minecraftPackages.size + fabricPackages.size} packages.`);
  }

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
  parserBackend: ParserBackend;
  packageNames: Set<string>;
  writtenPackageNames: Set<string>;
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
    parserBackend,
    packageNames,
    writtenPackageNames,
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

  if (sortedFiles.length === 0) {
    return processedFiles;
  }

  async function flushActivePackage(): Promise<void> {
    if (activePackage && activeClasses && Object.keys(activeClasses).length > 0) {
      await flushPackage(namespace, activePackage, activeClasses, version, packageNames, writtenPackageNames);
    }
    activePackage = null;
    activeClasses = null;
  }

  async function indexParsedClass(parsed: ParsedClass): Promise<void> {
    const packageName = parsed.packageName || 'default';

    if (activePackage !== null && packageName !== activePackage) {
      await flushActivePackage();
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

  if (parserBackend === 'java') {
    const batch = await parseJavaFilesWithWorker(sortedFiles.map(sourceFile));
    const fileOrder = new Map(sortedFiles.map((file, index) => [file.replace(/\\/g, '/'), index]));
    const parsedClasses = [...batch.parsed].sort((a, b) => {
      const aOrder = fileOrder.get(a.info.sourcePath) ?? Number.MAX_SAFE_INTEGER;
      const bOrder = fileOrder.get(b.info.sourcePath) ?? Number.MAX_SAFE_INTEGER;
      return aOrder - bOrder;
    });

    for (const parsed of parsedClasses) {
      await indexParsedClass(parsed);
    }
    reportProcessed(sortedFiles.length);
  } else if (shouldUseAstWorkers(sortedFiles.length, parserBackend)) {
    await parseJavaFilesInWorkerBatches(sortedFiles, async batch => {
      for (const parsed of batch.parsed) {
        await indexParsedClass(parsed);
      }
    }, reportProcessed);
  } else {
    for (const file of sortedFiles) {
      const parsed = parseJavaFileWithBackend(file, parserBackend);
      if (parsed) {
        await indexParsedClass(parsed);
      }
      reportProcessed(1);
    }
  }

  await flushActivePackage();
  return processedFiles;
}

async function flushPackage(
  namespace: 'minecraft' | 'fabric',
  packageName: string,
  classes: Record<string, ClassInfo>,
  version: string,
  packageNames: Set<string>,
  writtenPackageNames: Set<string>
): Promise<void> {
  packageNames.add(packageName);
  const key = `${namespace}:${packageName}`;
  if (writtenPackageNames.has(key)) return;
  writtenPackageNames.add(key);

  const packageIndex: PackageIndex = {
    package: packageName,
    classes,
  };

  const packagePath = getVersionedPackageIndexPath(version, namespace, packageName);
  fs.writeFileSync(packagePath, JSON.stringify(packageIndex, null, 2));
}

async function yieldForGc(): Promise<void> {
  await new Promise(resolve => setImmediate(resolve));
  if (global.gc) {
    global.gc();
  }
}

async function findJavaFiles(rootDir: string): Promise<string[]> {
  const pattern = path.join(rootDir, '**/*.java').replace(/\\/g, '/');
  return glob(pattern, { nodir: true, absolute: true });
}

function shouldUseAstWorkers(fileCount: number, backend: LegacyWorkerBackend): boolean {
  return backend === 'ast' && fileCount >= AST_WORKER_BATCH_SIZE;
}

interface AstWorkerBatch {
  parsed: ParsedClass[];
  failures: Array<{ file: string; error: string }>;
}

async function parseJavaFilesInWorkerBatches(
  files: string[],
  onBatch: (batch: AstWorkerBatch) => Promise<void>,
  reportProcessed: (count: number) => void
): Promise<void> {
  for (let start = 0; start < files.length; start += AST_WORKER_BATCH_SIZE) {
    const batchFiles = files.slice(start, start + AST_WORKER_BATCH_SIZE);
    const batch = await runAstWorkerBatch(batchFiles, AST_WORKER_HEAP_MB);
    await onBatch(batch);
    reportProcessed(batchFiles.length);
  }
}

async function runAstWorkerBatch(files: string[], heapMb: number): Promise<AstWorkerBatch> {
  const workerPath = fileURLToPath(new URL('./parse-worker.js', import.meta.url));

  return new Promise((resolve, reject) => {
    const child = fork(workerPath, [], {
      execArgv: [`--max-old-space-size=${heapMb}`],
      stdio: ['pipe', 'pipe', 'pipe', 'ipc'],
    });

    let stderr = '';
    let settled = false;

    function appendStderr(chunk: Buffer): void {
      stderr += chunk.toString('utf8');
      if (stderr.length > WORKER_STDERR_LIMIT) {
        stderr = stderr.slice(-WORKER_STDERR_LIMIT);
      }
    }

    function finish(error: Error | null, batch?: AstWorkerBatch): void {
      if (settled) return;
      settled = true;
      if (!child.killed) child.kill();
      if (error) reject(error);
      else resolve(batch ?? { parsed: [], failures: [] });
    }

    function errorWithDetails(message: string): Error {
      const detail = stderr.trim();
      return new Error(detail ? `${message}\nWorker stderr:\n${detail}` : message);
    }

    function formatFailures(failures: Array<{ file: string; error: string }>): string {
      return failures.map(failure => `${failure.file}: ${failure.error}`).join('\n');
    }

    child.stderr?.on('data', appendStderr);
    child.on('message', async message => {
      if (settled) return;
      const batch = message as AstWorkerBatch;
      if (batch.failures.length > 0 && heapMb < AST_WORKER_RETRY_HEAP_MB) {
        finish(null, await runAstWorkerBatch(files, AST_WORKER_RETRY_HEAP_MB));
        return;
      }
      if (batch.failures.length > 0) {
        finish(errorWithDetails(`AST worker failed to parse files:\n${formatFailures(batch.failures)}`));
        return;
      }
      finish(null, batch);
    });
    child.on('error', error => {
      finish(errorWithDetails(`AST worker failed to start: ${error.message}`));
    });
    child.on('exit', (code, signal) => {
      if (settled) return;
      const reason = signal ? `signal ${signal}` : `code ${code}`;
      finish(errorWithDetails(`AST worker exited before a result with ${reason}.`));
    });

    child.send({ files });
  });
}

export async function loadIndexManifest(minecraftVersion: string): Promise<IndexManifest | null> {
  return readJsonFileOrNull<IndexManifest>(getVersionedIndexManifestPath(minecraftVersion));
}
