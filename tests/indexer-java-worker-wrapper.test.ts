import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { parseJavaFilesWithWorker, sourceFile } from '../src/indexer/java-worker.js';

describe('Java indexer worker wrapper', () => {
  const originalCommand = process.env.MCDEV_JAVA_WORKER_COMMAND;
  const originalArgs = process.env.MCDEV_JAVA_WORKER_ARGS_JSON;
  let tempDir: string;

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcdev-java-wrapper-'));
  });

  afterEach(() => {
    if (originalCommand === undefined) delete process.env.MCDEV_JAVA_WORKER_COMMAND;
    else process.env.MCDEV_JAVA_WORKER_COMMAND = originalCommand;
    if (originalArgs === undefined) delete process.env.MCDEV_JAVA_WORKER_ARGS_JSON;
    else process.env.MCDEV_JAVA_WORKER_ARGS_JSON = originalArgs;
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  function useFakeWorker(source: string): void {
    const workerPath = path.join(tempDir, 'fake-worker.cjs');
    fs.writeFileSync(workerPath, source, 'utf8');
    process.env.MCDEV_JAVA_WORKER_COMMAND = process.execPath;
    process.env.MCDEV_JAVA_WORKER_ARGS_JSON = JSON.stringify([workerPath]);
  }

  test('rejects worker-reported parse failures with failure details', async () => {
    useFakeWorker(`
process.stdin.setEncoding('utf8');
let input = '';
process.stdin.on('data', chunk => { input += chunk; });
process.stdin.on('end', () => {
  const request = JSON.parse(input);
  process.stdout.write(JSON.stringify({
    id: request.id,
    parsed: [],
    failures: [{ file: { path: 'Broken.java' }, error: 'expected identifier' }]
  }) + '\\n');
});
`);

    await expect(parseJavaFilesWithWorker([sourceFile('Broken.java')])).rejects.toThrow(
      /Java worker failed to parse files:[\s\S]*Broken\.java: expected identifier/
    );
  });

  test('rejects invalid JSON emitted by the worker', async () => {
    useFakeWorker(`
process.stdout.write('{not json}\\n');
process.stdin.resume();
`);

    await expect(parseJavaFilesWithWorker([sourceFile('Example.java')])).rejects.toThrow(/invalid JSON/);
  });

  test('rejects a response id mismatch', async () => {
    useFakeWorker(`
process.stdin.setEncoding('utf8');
let input = '';
process.stdin.on('data', chunk => { input += chunk; });
process.stdin.on('end', () => {
  const request = JSON.parse(input);
  process.stdout.write(JSON.stringify({ id: request.id + 1, parsed: [], failures: [] }) + '\\n');
});
`);

    await expect(parseJavaFilesWithWorker([sourceFile('Example.java')])).rejects.toThrow(
      /response id mismatch: expected \d+, got \d+/
    );
  });

  test('rejects early close after draining stderr', async () => {
    useFakeWorker(`
process.stderr.write('first stderr line\\n');
setImmediate(() => {
  process.stderr.write('final stderr line\\n', () => process.exit(7));
});
process.stdin.resume();
`);

    await expect(parseJavaFilesWithWorker([sourceFile('Example.java')])).rejects.toThrow(
      /exited before a valid response with code 7[\s\S]*first stderr line[\s\S]*final stderr line/
    );
  });

  test('does not spawn a worker for an empty batch', async () => {
    const markerPath = path.join(tempDir, 'worker-started');
    useFakeWorker(`
require('fs').writeFileSync(${JSON.stringify(markerPath)}, 'started');
process.stdin.resume();
`);

    await expect(parseJavaFilesWithWorker([])).resolves.toEqual({ parsed: [], failures: [] });
    expect(fs.existsSync(markerPath)).toBe(false);
  });
});
