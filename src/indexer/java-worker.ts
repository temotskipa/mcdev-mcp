import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import type { ParsedClass } from './parser.js';
import { assertJavaAtLeast, MIN_SUPPORTED_JAVA } from '../utils/java.js';

const WORKER_STDERR_LIMIT = 8000;
let nextRequestId = 1;

export interface JavaWorkerSourceFile {
  path: string;
}

export interface JavaWorkerFailure {
  file: JavaWorkerSourceFile;
  error: string;
}

export interface JavaWorkerBatch {
  parsed: ParsedClass[];
  failures: JavaWorkerFailure[];
}

interface JavaWorkerResponse extends JavaWorkerBatch {
  id: number;
}

export function sourceFile(path: string): JavaWorkerSourceFile {
  return { path };
}

export function parseJavaFilesWithWorker(files: JavaWorkerSourceFile[]): Promise<JavaWorkerBatch> {
  if (files.length === 0) {
    return Promise.resolve({ parsed: [], failures: [] });
  }

  assertJavaAtLeast('java', MIN_SUPPORTED_JAVA, 'Java indexer');

  const id = nextRequestId++;
  const workerPath = getJavaWorkerPath();
  const workerCommand = getJavaWorkerCommand();
  const workerArgs = getJavaWorkerArgs(workerPath);

  return new Promise((resolve, reject) => {
    const child = spawn(workerCommand, workerArgs, {
      cwd: process.cwd(),
      stdio: ['pipe', 'pipe', 'pipe'],
    });

    let stdout = '';
    let stderr = '';
    let settled = false;

    function appendStderr(chunk: Buffer): void {
      stderr += chunk.toString('utf8');
      if (stderr.length > WORKER_STDERR_LIMIT) {
        stderr = stderr.slice(-WORKER_STDERR_LIMIT);
      }
    }

    function errorWithDetails(message: string): Error {
      const detail = stderr.trim();
      return new Error(detail ? `${message}\nWorker stderr:\n${detail}` : message);
    }

    function finish(error: Error | null, batch?: JavaWorkerBatch): void {
      if (settled) return;
      settled = true;
      if (!child.stdin.destroyed && !child.stdin.writableEnded) child.stdin.end();
      if (error) reject(error);
      else resolve(batch ?? { parsed: [], failures: [] });
    }

    function formatFailures(failures: JavaWorkerFailure[]): string {
      return failures
        .map(failure => `${formatSourceFile(failure.file)}: ${failure.error}`)
        .join('\n');
    }

    child.stderr.on('data', appendStderr);

    child.stdout.on('data', (chunk: Buffer) => {
      if (settled) return;
      stdout += chunk.toString('utf8');
      const newline = stdout.indexOf('\n');
      if (newline === -1) return;

      const line = stdout.slice(0, newline).trim();
      let response: JavaWorkerResponse;
      try {
        response = JSON.parse(line) as JavaWorkerResponse;
      } catch (error) {
        const cause = error instanceof Error ? error.message : String(error);
        finish(errorWithDetails(`Java worker emitted invalid JSON: ${cause}`));
        return;
      }

      if (response.id !== id) {
        const failures = Array.isArray(response.failures) && response.failures.length > 0
          ? `\nWorker failures:\n${formatFailures(response.failures)}`
          : '';
        finish(errorWithDetails(`Java worker response id mismatch: expected ${id}, got ${response.id}.${failures}`));
        return;
      }

      if (!Array.isArray(response.parsed) || !Array.isArray(response.failures)) {
        finish(errorWithDetails('Java worker response is missing parsed or failures arrays.'));
        return;
      }

      if (response.failures.length > 0) {
        finish(errorWithDetails(`Java worker failed to parse files:\n${formatFailures(response.failures)}`));
        return;
      }

      finish(null, { parsed: response.parsed, failures: response.failures });
    });

    child.on('error', error => {
      finish(errorWithDetails(`Java worker failed to start: ${error.message}`));
    });

    child.on('close', (code, signal) => {
      if (settled) return;
      const reason = signal ? `signal ${signal}` : `code ${code}`;
      finish(errorWithDetails(`Java worker exited before a valid response with ${reason}.`));
    });

    child.stdin.end(`${JSON.stringify({ id, files })}\n`);
  });
}

function formatSourceFile(file: JavaWorkerSourceFile): string {
  return file.path;
}

function getJavaWorkerPath(): string {
  const packagedWorker = fileURLToPath(new URL('../java/mcdev-java-worker.jar', import.meta.url));
  if (existsSync(packagedWorker)) return packagedWorker;

  return fileURLToPath(new URL('../../java-worker/build/libs/mcdev-java-worker.jar', import.meta.url));
}

function getJavaWorkerCommand(): string {
  return process.env.MCDEV_JAVA_WORKER_COMMAND || 'java';
}

function getJavaWorkerArgs(workerPath: string): string[] {
  const override = process.env.MCDEV_JAVA_WORKER_ARGS_JSON;
  if (override) {
    const parsed = JSON.parse(override) as unknown;
    if (!Array.isArray(parsed) || !parsed.every(item => typeof item === 'string')) {
      throw new Error('MCDEV_JAVA_WORKER_ARGS_JSON must be a JSON array of strings.');
    }
    return parsed;
  }

  return ['-jar', workerPath];
}
