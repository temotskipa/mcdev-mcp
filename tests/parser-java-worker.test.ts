import { spawn, spawnSync, type ChildProcessWithoutNullStreams } from 'node:child_process';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const workerJarPath = path.join(repoRoot, 'java-worker', 'build', 'libs', 'mcdev-java-worker.jar');

function gradleCommand(): string {
  return process.platform === 'win32' ? 'gradle.bat' : 'gradle';
}

type WorkerResponse = {
  id: number;
  parsed: Array<{
    packageName: string;
    className: string;
    fullName: string;
    info: {
      kind: string;
      super: string | null;
      interfaces: string[];
      fields: Array<{ name: string; type: string; modifiers: string[] }>;
      methods: Array<{
        name: string;
        returnType: string;
        params: Array<{ name: string; type: string }>;
        modifiers: string[];
        lineStart: number;
        lineEnd: number;
      }>;
      sourcePath: string;
    };
  }>;
  failures: Array<{ file: string; error: string }>;
};

function startWorker(): ChildProcessWithoutNullStreams {
  return spawn('java', ['-jar', workerJarPath], {
    cwd: repoRoot,
    stdio: ['pipe', 'pipe', 'pipe'],
  });
}

function waitForJsonLine(worker: ChildProcessWithoutNullStreams): Promise<WorkerResponse> {
  return new Promise((resolve, reject) => {
    let stdout = '';
    let stderr = '';

    const cleanup = () => {
      worker.stdout.off('data', onStdout);
      worker.stderr.off('data', onStderr);
      worker.off('error', onError);
      worker.off('exit', onExit);
    };

    const onStdout = (chunk: Buffer) => {
      stdout += chunk.toString('utf8');
      const newline = stdout.indexOf('\n');
      if (newline === -1) return;

      const line = stdout.slice(0, newline).trim();
      cleanup();
      try {
        resolve(JSON.parse(line) as WorkerResponse);
      } catch (error) {
        reject(error);
      }
    };

    const onStderr = (chunk: Buffer) => {
      stderr += chunk.toString('utf8');
    };

    const onError = (error: Error) => {
      cleanup();
      reject(error);
    };

    const onExit = (code: number | null) => {
      cleanup();
      reject(new Error(`Worker exited before response with code ${code}: ${stderr}`));
    };

    worker.stdout.on('data', onStdout);
    worker.stderr.on('data', onStderr);
    worker.once('error', onError);
    worker.once('exit', onExit);
  });
}

function waitForExit(worker: ChildProcessWithoutNullStreams): Promise<number | null> {
  return new Promise((resolve, reject) => {
    worker.once('error', reject);
    worker.once('exit', resolve);
  });
}

function writeRequest(worker: ChildProcessWithoutNullStreams, id: number, files: string[]): void {
  worker.stdin.write(`${JSON.stringify({ id, files })}\n`);
}

describe('JavaIndexerWorker', () => {
  let tempDir: string;

  beforeAll(() => {
    const result = spawnSync(gradleCommand(), ['-p', 'java-worker', 'shadowJar'], {
      cwd: repoRoot,
      encoding: 'utf8',
    });

    if (result.status !== 0) {
      throw new Error(
        `Failed to build Java worker jar.\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`
      );
    }
  }, 120_000);

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcdev-java-worker-'));
  });

  afterEach(() => {
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  test('parses a record with direct members', async () => {
    const file = path.join(tempDir, 'ChatType.java');
    fs.writeFileSync(
      file,
      `
package net.minecraft.network.chat;

public record ChatType(ChatTypeDecoration chat, ChatTypeDecoration narration)
        implements Message, FormattedText {
    public static final int LIMIT = 2;
    public String render(String name, int count) { return name + count; }
}
`,
      'utf8'
    );

    const worker = startWorker();
    const responsePromise = waitForJsonLine(worker);
    const exitPromise = waitForExit(worker);

    writeRequest(worker, 1, [file]);

    const response = await responsePromise;
    worker.stdin.end();
    await expect(exitPromise).resolves.toBe(0);

    expect(response.id).toBe(1);
    expect(response.failures).toEqual([]);
    expect(response.parsed).toHaveLength(1);

    const parsed = response.parsed[0];
    expect(parsed.packageName).toBe('net.minecraft.network.chat');
    expect(parsed.className).toBe('ChatType');
    expect(parsed.info.kind).toBe('record');
    expect(parsed.info.interfaces).toEqual(['Message', 'FormattedText']);
    expect(parsed.info.fields.map(field => field.name).sort()).toEqual(['LIMIT', 'chat', 'narration']);

    const render = parsed.info.methods.find(method => method.name === 'render');
    expect(render?.params).toEqual([
      { name: 'name', type: 'String' },
      { name: 'count', type: 'int' },
    ]);
  });

  test('does not emit local variables from initializer blocks as fields', async () => {
    const file = path.join(tempDir, 'Initializers.java');
    fs.writeFileSync(
      file,
      `
package net.minecraft.network.chat;

public class Initializers {
    private int value;

    static {
        int staticLocal = 1;
    }

    {
        String instanceLocal = "hidden";
    }
}
`,
      'utf8'
    );

    const worker = startWorker();
    const responsePromise = waitForJsonLine(worker);
    const exitPromise = waitForExit(worker);

    writeRequest(worker, 2, [file]);

    const response = await responsePromise;
    worker.stdin.end();
    await expect(exitPromise).resolves.toBe(0);

    expect(response.id).toBe(2);
    expect(response.failures).toEqual([]);
    expect(response.parsed).toHaveLength(1);
    expect(response.parsed[0].info.fields.map(field => field.name)).toEqual(['value']);
  });

  test('reports parse failures per file and handles a later valid request', async () => {
    const file = path.join(tempDir, 'Broken.java');
    const validFile = path.join(tempDir, 'Recovered.java');
    fs.writeFileSync(
      file,
      `
package net.minecraft.network.chat;

public class Broken {
    public void nope( {
}
`,
      'utf8'
    );
    fs.writeFileSync(
      validFile,
      `
package net.minecraft.network.chat;

public class Recovered {
    public int value;
}
`,
      'utf8'
    );

    const worker = startWorker();
    const responsePromise = waitForJsonLine(worker);
    const exitPromise = waitForExit(worker);

    writeRequest(worker, 3, [file]);

    const response = await responsePromise;
    expect(response.id).toBe(3);
    expect(response.parsed).toEqual([]);
    expect(response.failures).toHaveLength(1);
    expect(response.failures[0].file).toBe(file);
    expect(response.failures[0].error).toEqual(expect.any(String));

    const recoveredResponsePromise = waitForJsonLine(worker);
    writeRequest(worker, 4, [validFile]);

    const recoveredResponse = await recoveredResponsePromise;
    expect(recoveredResponse.id).toBe(4);
    expect(recoveredResponse.failures).toEqual([]);
    expect(recoveredResponse.parsed).toHaveLength(1);
    expect(recoveredResponse.parsed[0].className).toBe('Recovered');
    expect(recoveredResponse.parsed[0].info.fields.map(field => field.name)).toEqual(['value']);

    worker.stdin.end();
    await expect(exitPromise).resolves.toBe(0);
  });
});
