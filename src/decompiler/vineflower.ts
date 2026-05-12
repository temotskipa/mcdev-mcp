import { spawn } from 'child_process';
import type { ProgressCallback } from './tools.js';
import { ensureDir } from '../utils/paths.js';

// Tail size for Vineflower stdout/stderr. The decompiler can chatter on huge
// jars; keep enough text to debug a failure without unbounded memory growth.
const TAIL_BYTES = 64 * 1024;

function appendTail(buf: string, chunk: string): string {
  const next = buf + chunk;
  return next.length > TAIL_BYTES ? next.slice(next.length - TAIL_BYTES) : next;
}

export async function decompile(
  vineflowerJar: string,
  inputJar: string,
  outputDir: string,
  progressCb?: ProgressCallback
): Promise<void> {
  ensureDir(outputDir);

  if (progressCb) {
    progressCb('decompile', 0, 'Decompiling with Vineflower (8 threads)...');
  }

  return new Promise((resolve, reject) => {
    const args = [
      '-jar', vineflowerJar,
      '-j=8',
      '--decompile-generics=1',
      '--bytecode-source-mapping=1',
      '--remove-synthetic=1',
      '--log-level=error',
      inputJar,
      outputDir
    ];

    // Capture stdout / stderr instead of discarding them. Previously this used
    // `'ignore'` for both, which made Vineflower failures undebuggable from
    // logs — `Vineflower failed (exit code 1)` was the only signal.
    const proc = spawn('java', args, {
      stdio: ['inherit', 'pipe', 'pipe'],
    });

    let stdout = '';
    let stderr = '';
    proc.stdout?.on('data', (data: Buffer) => { stdout = appendTail(stdout, data.toString()); });
    proc.stderr?.on('data', (data: Buffer) => { stderr = appendTail(stderr, data.toString()); });

    proc.on('close', (code) => {
      if (code === 0) {
        if (progressCb) progressCb('decompile', 100, 'Decompilation complete.');
        resolve();
      } else {
        const tail = (stderr.trim() || stdout.trim() || '(no output captured)').slice(-TAIL_BYTES);
        reject(new Error(`Vineflower failed (exit code ${code}). Last output:\n${tail}`));
      }
    });

    proc.on('error', (err) => {
      reject(new Error(`Failed to run Vineflower: ${err.message}`));
    });
  });
}
