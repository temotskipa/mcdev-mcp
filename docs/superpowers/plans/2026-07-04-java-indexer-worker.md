# Java Indexer Worker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the TypeScript `java-parser` AST indexer with a pure Java parser worker, make Java the default backend, keep regex only as an explicit legacy backend with no Java-to-regex fallback, require Java 25, and replace java-callgraph2 with a focused Java 25 callgraph worker that fully satisfies `mc_find_refs`.

**Architecture:** TypeScript remains the indexing orchestrator and package JSON writer. A long-lived Java 25 worker parses batches of source file paths with JDK compiler APIs and emits the existing `ParsedClass` shape as JSON lines. A sibling Java 25 callgraph worker scans class files in the Minecraft jar with `java.lang.classfile`, emits invocation edges, and TypeScript writes the existing SQLite `calls` schema used by `mc_find_refs`.

**Tech Stack:** TypeScript, Node child processes, Java 25 compiler APIs (`JavacTask`, `TreeScanner`), Java 25 Class-File API (`java.lang.classfile`), sql.js, Jest, npm build scripts.

---

## File Structure

- Create `src/indexer/java-worker/JavaIndexerWorker.java`: pure Java 25 worker process, JSONL protocol, compiler API parser, JSON emitter.
- Create `src/indexer/java-worker.ts`: TypeScript process wrapper for the Java worker, request/response typing, Java runtime checks.
- Create `src/callgraph/java-worker/CallgraphWorker.java`: pure Java 25 Class-File API worker that scans jar/class files and emits method invocation edges.
- Create `src/callgraph/java-worker.ts`: TypeScript process wrapper for the callgraph worker.
- Create `src/utils/java.ts`: shared Java runtime/version detection helpers for indexer and callgraph commands.
- Modify `src/indexer/parser.ts`: backend selection becomes `java|regex`; regex parser remains explicit legacy path.
- Modify `src/indexer/index.ts`: use Java worker batches for default backend; remove Node AST worker and regex fallback-on-AST-failure code.
- Modify `src/utils/types.ts`: manifest `indexerVersion` supports `'java'`, old `'ast'`, and `'regex'`.
- Modify `src/storage/source-store.ts`: stale index hint compares old manifests against the active backend and describes Java backend accurately.
- Modify `src/callgraph/index.ts`: require Java 25, remove java-callgraph2 clone/build, run the Java callgraph worker, stream edges into SQLite, create indexes after inserts.
- Modify `package.json`: add Java worker build step, remove `java-parser` dependency, keep TypeScript build.
- Modify `tsconfig.json` only if needed for new TypeScript module placement.
- Modify `README.md`: replace AST preview docs with Java backend docs and legacy regex override.
- Modify `tests/parser-ast.test.ts`: migrate to Java worker tests or replace with `tests/parser-java-worker.test.ts`.
- Modify `tests/parser.test.ts`, `tests/parser-declaration.test.ts`, and `tests/indexer.test.ts`: align defaults, backend env vars, and worker behavior.

---

### Task 1: Backend Selection Contract

**Files:**
- Modify: `src/indexer/parser.ts`
- Modify: `src/utils/types.ts`
- Test: `tests/parser.test.ts`

- [ ] **Step 1: Write failing backend-selection tests**

Add tests to `tests/parser.test.ts` that prove Java is default and regex is explicit:

```ts
describe('Indexer backend selection', () => {
  const originalIndexer = process.env.MCDEV_INDEXER;
  const originalAst = process.env.MCDEV_AST_PARSER;

  afterEach(() => {
    if (originalIndexer === undefined) delete process.env.MCDEV_INDEXER;
    else process.env.MCDEV_INDEXER = originalIndexer;
    if (originalAst === undefined) delete process.env.MCDEV_AST_PARSER;
    else process.env.MCDEV_AST_PARSER = originalAst;
  });

  test('defaults to java backend', async () => {
    delete process.env.MCDEV_INDEXER;
    delete process.env.MCDEV_AST_PARSER;
    const mod = await import('../src/indexer/parser.js');
    expect(mod.getParserBackend()).toBe('java');
  });

  test('selects regex only through MCDEV_INDEXER=regex', async () => {
    process.env.MCDEV_INDEXER = 'regex';
    const mod = await import('../src/indexer/parser.js');
    expect(mod.getParserBackend()).toBe('regex');
  });

  test('ignores legacy MCDEV_AST_PARSER as a backend selector', async () => {
    delete process.env.MCDEV_INDEXER;
    process.env.MCDEV_AST_PARSER = '1';
    const mod = await import('../src/indexer/parser.js');
    expect(mod.getParserBackend()).toBe('java');
  });
});
```

- [ ] **Step 2: Run test and verify failure**

Run:

```bash
npm test -- --runInBand tests/parser.test.ts
```

Expected: failure because `getParserBackend()` currently returns `'regex'` by default and still checks `MCDEV_AST_PARSER`.

- [ ] **Step 3: Update parser backend type and selector**

In `src/indexer/parser.ts`, change the backend type and selector:

```ts
export type ParserBackend = 'java' | 'regex';

export function getParserBackend(): ParserBackend {
  return process.env.MCDEV_INDEXER === 'regex' ? 'regex' : 'java';
}
```

Keep `parseJavaFileWithBackend(filePath, 'regex')` and `parseJavaContentWithBackend(content, filePath, 'regex')` working for legacy tests. For `'java'`, make these synchronous content/file helpers throw a clear error until Task 3 wires the async Java worker:

```ts
if (backend === 'java') {
  throw new Error('The Java indexer backend is only available through buildIndex(). Use MCDEV_INDEXER=regex for synchronous parser helpers.');
}
```

- [ ] **Step 4: Update manifest type**

In `src/utils/types.ts`, change:

```ts
indexerVersion?: 'regex' | 'ast';
```

to:

```ts
indexerVersion?: 'regex' | 'ast' | 'java';
```

Keep `'ast'` so existing manifests remain readable.

- [ ] **Step 5: Run backend-selection tests**

Run:

```bash
npm test -- --runInBand tests/parser.test.ts
```

Expected: backend-selection tests pass; older parser tests that call default `parseJavaContent()` may fail until Task 3 migrates them to the Java worker or explicit regex backend.

- [ ] **Step 6: Commit**

```bash
git add src/indexer/parser.ts src/utils/types.ts tests/parser.test.ts
git commit -m "feat: make java the default indexer backend"
```

---

### Task 2: Java Worker Parser

**Files:**
- Create: `src/indexer/java-worker/JavaIndexerWorker.java`
- Test: `tests/parser-java-worker.test.ts`

- [ ] **Step 1: Write failing worker integration tests**

Create `tests/parser-java-worker.test.ts`:

```ts
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { spawn } from 'child_process';

function workerSourcePath(): string {
  return path.join(process.cwd(), 'src', 'indexer', 'java-worker', 'JavaIndexerWorker.java');
}

function sendWorkerRequest(files: string[]): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const child = spawn('java', [workerSourcePath()], {
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', chunk => { stdout += chunk.toString(); });
    child.stderr.on('data', chunk => { stderr += chunk.toString(); });
    child.on('error', reject);
    child.on('exit', code => {
      if (code !== 0) {
        reject(new Error(`worker exited ${code}: ${stderr}`));
        return;
      }
      const line = stdout.trim().split('\n').at(-1);
      resolve(JSON.parse(line || '{}'));
    });
    child.stdin.write(JSON.stringify({ id: 1, files }) + '\n');
    child.stdin.end();
  });
}

describe('Java indexer worker', () => {
  const tempDir = path.join(os.tmpdir(), `mcdev-java-worker-${Date.now()}`);

  beforeEach(() => fs.mkdirSync(tempDir, { recursive: true }));
  afterEach(() => fs.rmSync(tempDir, { recursive: true, force: true }));

  test('parses modern Java features into ParsedClass summaries', async () => {
    const file = path.join(tempDir, 'ChatType.java');
    fs.writeFileSync(file, `
package net.minecraft.network.chat;
public record ChatType(ChatTypeDecoration chat, ChatTypeDecoration narration)
        implements Message, FormattedText {
    public static final int LIMIT = 2;
    public String render(String name, int count) { return name + count; }
}
`);

    const response = await sendWorkerRequest([file]) as {
      id: number;
      parsed: Array<{
        packageName: string;
        className: string;
        info: {
          kind: string;
          interfaces: string[];
          fields: Array<{ name: string; type: string; modifiers: string[] }>;
          methods: Array<{ name: string; returnType: string; params: Array<{ name: string; type: string }> }>;
        };
      }>;
      failures: unknown[];
    };

    expect(response.id).toBe(1);
    expect(response.failures).toEqual([]);
    expect(response.parsed[0].packageName).toBe('net.minecraft.network.chat');
    expect(response.parsed[0].className).toBe('ChatType');
    expect(response.parsed[0].info.kind).toBe('record');
    expect(response.parsed[0].info.interfaces).toEqual(['Message', 'FormattedText']);
    expect(response.parsed[0].info.fields.map(f => f.name).sort()).toEqual(['LIMIT', 'chat', 'narration']);
    expect(response.parsed[0].info.methods.find(m => m.name === 'render')?.params).toEqual([
      { name: 'name', type: 'String' },
      { name: 'count', type: 'int' },
    ]);
  }, 30000);
});
```

- [ ] **Step 2: Run test and verify failure**

Run:

```bash
npm test -- --runInBand tests/parser-java-worker.test.ts
```

Expected: failure because `src/indexer/java-worker/JavaIndexerWorker.java` does not exist.

- [ ] **Step 3: Implement worker protocol and parser**

Create `src/indexer/java-worker/JavaIndexerWorker.java` with these responsibilities:

```java
// Use a single public class named JavaIndexerWorker.
// main(): read one JSON request per line from stdin, parse files, print one JSON response per line.
// parseFile(Path): use ToolProvider.getSystemJavaCompiler(), JavacTask.parse(), and a TreeScanner.
// The scanner captures packageName, first top-level type, direct fields, direct methods, record components,
// superclass, interfaces, modifiers, parameter names/types, and line ranges.
// Escape JSON strings manually with a json(String value) helper; do not add external dependencies.
// Use Java 25 as the supported runtime target for build and runtime checks.
```

The response shape must be exactly:

```json
{
  "id": 1,
  "parsed": [
    {
      "packageName": "x",
      "className": "C",
      "fullName": "x.C",
      "info": {
        "kind": "class",
        "super": null,
        "interfaces": [],
        "fields": [],
        "methods": [],
        "sourcePath": "/absolute/or/input/path/C.java"
      }
    }
  ],
  "failures": []
}
```

On per-file parse errors, include:

```json
{
  "file": "/path/Bad.java",
  "error": "message"
}
```

and keep the worker process alive for future requests.

- [ ] **Step 4: Run worker tests**

Run:

```bash
npm test -- --runInBand tests/parser-java-worker.test.ts
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/indexer/java-worker/JavaIndexerWorker.java tests/parser-java-worker.test.ts
git commit -m "feat: add Java indexer worker"
```

---

### Task 3: TypeScript Java Worker Wrapper

**Files:**
- Create: `src/indexer/java-worker.ts`
- Modify: `src/indexer/index.ts`
- Test: `tests/indexer.test.ts`

- [ ] **Step 1: Write failing buildIndex Java-backend test**

Add a test to `tests/indexer.test.ts`:

```ts
test('builds a java-backed manifest without regex fallback', async () => {
  process.env.MCDEV_INDEXER = 'java';
  const version = `java-worker-index-${Date.now()}`;
  const pkgDir = path.join(tempDir, 'javaworker');
  fs.mkdirSync(pkgDir, { recursive: true });
  fs.writeFileSync(path.join(pkgDir, 'WorkerBacked.java'), `
package worker.backed;
public interface WorkerBacked {
    int LIMIT = 4;
    default int doubled() { return LIMIT * 2; }
}
`);

  const result = await buildIndex({
    minecraftSourceDir: tempDir,
    fabricApiSourceDir: null,
    minecraftVersion: version,
    fabricApiVersion: null,
  });

  expect(result.totalClasses).toBe(1);
  const manifest = readJsonFileOrNull<IndexManifest>(
    getVersionedIndexManifestPath(version),
    'test/java-worker-manifest'
  );
  expect(manifest?.indexerVersion).toBe('java');
  const indexed = loadPackageIndex('minecraft', 'worker.backed', version);
  expect(indexed?.classes.WorkerBacked.info).toBeUndefined();
  expect(indexed?.classes.WorkerBacked.fields.map(f => f.name)).toEqual(['LIMIT']);
  expect(indexed?.classes.WorkerBacked.methods.map(m => m.name)).toEqual(['doubled']);
});
```

If the `ClassInfo` path in the assertion is wrong, use the actual `PackageIndex` shape:

```ts
expect(indexed?.classes.WorkerBacked.fields.map(f => f.name)).toEqual(['LIMIT']);
```

- [ ] **Step 2: Run test and verify failure**

Run:

```bash
npm test -- --runInBand tests/indexer.test.ts
```

Expected: failure because `buildIndex()` does not yet call the Java worker.

- [ ] **Step 3: Implement `parseJavaFilesWithWorker()` wrapper**

Create `src/indexer/java-worker.ts` exporting:

```ts
import { spawn, type ChildProcessWithoutNullStreams } from 'child_process';
import * as path from 'path';
import { fileURLToPath } from 'url';
import type { ParsedClass } from './parser.js';

export interface JavaWorkerFailure {
  file: string;
  error: string;
}

export interface JavaWorkerBatch {
  parsed: ParsedClass[];
  failures: JavaWorkerFailure[];
}

export async function parseJavaFilesWithWorker(files: string[]): Promise<JavaWorkerBatch> {
  const worker = new JavaIndexerWorkerProcess();
  try {
    return await worker.parse(files);
  } finally {
    worker.close();
  }
}
```

The wrapper should:

- Spawn `java` with the compiled worker class or source-file fallback during tests.
- Send one JSON request line with `{ id, files }`.
- Parse one JSON response line.
- Reject if the worker exits, emits invalid JSON, or reports `failures.length > 0`.
- Include stderr in thrown errors.

- [ ] **Step 4: Wire `buildIndex()` to Java backend**

In `src/indexer/index.ts`:

- Import `parseJavaFilesWithWorker`.
- Remove AST Node worker constants and functions.
- In `processJavaFiles()`, when `parserBackend === 'java'`, call the Java worker path and index returned parsed classes in sorted order.
- Keep the existing regex loop only when `parserBackend === 'regex'`.
- Stamp manifest `indexerVersion: parserBackend`.

No Java backend code may call `parseJavaFileWithBackend(file, 'regex')`.

- [ ] **Step 5: Run indexer tests**

Run:

```bash
npm test -- --runInBand tests/indexer.test.ts
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/indexer/java-worker.ts src/indexer/index.ts tests/indexer.test.ts
git commit -m "feat: use Java worker for symbol indexing"
```

---

### Task 4: Java 25 Runtime Checks and Worker Build

**Files:**
- Create: `src/utils/java.ts`
- Modify: `package.json`
- Modify: `package-lock.json`
- Modify: `src/callgraph/index.ts`
- Test: `tests/java-runtime.test.ts`

- [ ] **Step 1: Write failing Java version helper tests**

Create `tests/java-runtime.test.ts`:

```ts
import { parseJavaMajorVersion, assertJavaAtLeast } from '../src/utils/java.js';

describe('Java runtime helpers', () => {
  test('parses modern Java version output', () => {
    expect(parseJavaMajorVersion('openjdk version "25.0.1" 2026-10-21')).toBe(25);
    expect(parseJavaMajorVersion('java version "26-ea"')).toBe(26);
  });

  test('parses legacy Java version output', () => {
    expect(parseJavaMajorVersion('java version "1.8.0_402"')).toBe(8);
  });

  test('requires Java 25 or newer', () => {
    expect(() => assertJavaAtLeast('java', 25, 'indexer', 'openjdk version "24.0.2"')).toThrow(/Java 25\\+/);
    expect(() => assertJavaAtLeast('java', 25, 'indexer', 'openjdk version "25.0.1"')).not.toThrow();
  });
});
```

- [ ] **Step 2: Run test and verify failure**

Run:

```bash
npm test -- --runInBand tests/java-runtime.test.ts
```

Expected: failure because `src/utils/java.ts` does not exist.

- [ ] **Step 3: Implement shared Java helper**

Create `src/utils/java.ts`:

```ts
import { spawnSync } from 'child_process';

export const MIN_SUPPORTED_JAVA = 25;

export function parseJavaMajorVersion(output: string): number | null {
  const match = output.match(/version\\s+"([^"]+)"/i) ?? output.match(/(?:openjdk|java)\\s+([0-9][^\\s]*)/i);
  const raw = match?.[1];
  if (!raw) return null;
  if (raw.startsWith('1.')) {
    const legacy = Number.parseInt(raw.split('.')[1] ?? '', 10);
    return Number.isFinite(legacy) ? legacy : null;
  }
  const major = Number.parseInt(raw, 10);
  return Number.isFinite(major) ? major : null;
}

export function assertJavaAtLeast(
  javaCommand: string,
  minMajor: number,
  purpose: string,
  versionOutput?: string,
): void {
  const output = versionOutput ?? readJavaVersion(javaCommand);
  const major = parseJavaMajorVersion(output);
  if (major === null || major < minMajor) {
    throw new Error(`${purpose} requires Java ${minMajor}+ with JDK compiler APIs. Found: ${output.trim() || 'unknown Java version'}`);
  }
}

function readJavaVersion(javaCommand: string): string {
  const result = spawnSync(javaCommand, ['-version'], { encoding: 'utf-8' });
  if (result.error) {
    throw new Error(`Failed to run ${javaCommand} -version: ${result.error.message}`);
  }
  return `${result.stderr ?? ''}${result.stdout ?? ''}`;
}
```

- [ ] **Step 4: Add build script expectation**

Confirm this command fails before adding the script:

```bash
npm run build:java-worker
```

Expected: npm reports missing script.

- [ ] **Step 5: Add Java compile scripts**

Modify `package.json` scripts:

```json
{
  "scripts": {
    "build": "npm run build:java-worker && tsc",
    "build:ts": "tsc",
    "build:java-worker": "node scripts/build-java-worker.mjs"
  }
}
```

Create `scripts/build-java-worker.mjs` if direct shell quoting would be fragile on Windows:

```js
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const workers = [
  {
    src: path.join('src', 'indexer', 'java-worker', 'JavaIndexerWorker.java'),
    outDir: path.join('dist', 'indexer', 'java-worker'),
  },
  {
    src: path.join('src', 'callgraph', 'java-worker', 'CallgraphWorker.java'),
    outDir: path.join('dist', 'callgraph', 'java-worker'),
  },
];

for (const worker of workers) {
  if (!fs.existsSync(worker.src)) continue;
  fs.mkdirSync(worker.outDir, { recursive: true });
  const result = spawnSync('javac', ['--release', '25', '-d', worker.outDir, worker.src], { stdio: 'inherit' });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}
```

Ensure `scripts/build-java-worker.mjs` is included in the npm package through the existing `"files"` behavior or by adding `"scripts/build-java-worker.mjs"` if needed.

- [ ] **Step 6: Update wrapper to require Java 25 and prefer compiled class**

In `src/indexer/java-worker.ts`, call:

```ts
assertJavaAtLeast('java', MIN_SUPPORTED_JAVA, 'Java indexer');
```

then resolve:

```ts
const compiledDir = fileURLToPath(new URL('./java-worker/', import.meta.url));
const sourceFile = path.resolve(process.cwd(), 'src/indexer/java-worker/JavaIndexerWorker.java');
```

Prefer:

```bash
java -cp <compiledDir> JavaIndexerWorker
```

Fallback for tests/dev when compiled class does not exist:

```bash
java <sourceFile>
```

- [ ] **Step 7: Require Java 25 in callgraph path**

In `src/callgraph/index.ts`, call the shared helper before spawning the Java callgraph worker:

```ts
import { assertJavaAtLeast, MIN_SUPPORTED_JAVA } from '../utils/java.js';

assertJavaAtLeast('java', MIN_SUPPORTED_JAVA, 'Callgraph generation');
```

Place this at the start of `generateCallgraph()` so the command fails before expensive work. Avoid duplicate checks inside tight loops.

- [ ] **Step 8: Run Java helper tests**

Run:

```bash
npm test -- --runInBand tests/java-runtime.test.ts
```

Expected: PASS.

- [ ] **Step 9: Run Java build and TypeScript build**

Run:

```bash
npm run build
```

Expected on Java 25+: `dist/indexer/java-worker/JavaIndexerWorker.class` exists and TypeScript compiles.

Expected on older Java: clear failure mentioning Java 25+.

- [ ] **Step 10: Commit**

```bash
git add package.json package-lock.json scripts/build-java-worker.mjs src/indexer/java-worker.ts src/utils/java.ts src/callgraph/index.ts tests/java-runtime.test.ts
git commit -m "build: require Java 25 for Java tooling"
```

---

### Task 5: Remove Old AST Backend

**Files:**
- Delete: `src/indexer/parser-ast.ts`
- Delete: `src/indexer/parse-worker.ts`
- Modify: `package.json`
- Modify: `package-lock.json`
- Modify: `tests/parser-ast.test.ts`

- [ ] **Step 1: Remove AST files and dependency**

Delete:

```text
src/indexer/parser-ast.ts
src/indexer/parse-worker.ts
```

Remove from `package.json` dependencies:

```json
"java-parser": "^3.0.1"
```

Run:

```bash
npm install
```

Expected: `package-lock.json` no longer contains the root `java-parser` dependency.

- [ ] **Step 2: Replace AST tests with Java worker tests**

Either delete `tests/parser-ast.test.ts` or convert it to import the Java worker wrapper. The converted tests should call `buildIndex()` or `parseJavaFilesWithWorker()` instead of `parseJavaContentAst()`.

The test names should start with:

```ts
describe('Java worker parser — modern Java source forms', () => {
  // migrated cases from the old AST parser suite
});
```

- [ ] **Step 3: Search for forbidden references**

Run:

```bash
rg -n "parser-ast|parse-worker|java-parser|MCDEV_AST_PARSER|ast worker|AST parser worker" src tests README.md package.json
```

Expected: no implementation references remain. README may mention old AST manifests only if explaining migration.

- [ ] **Step 4: Run tests**

Run:

```bash
npm test -- --runInBand
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/indexer tests package.json package-lock.json
git commit -m "refactor: remove TypeScript AST parser backend"
```

---

### Task 6: Java Callgraph Worker

**Files:**
- Create: `src/callgraph/java-worker/CallgraphWorker.java`
- Create: `src/callgraph/java-worker.ts`
- Modify: `src/callgraph/index.ts`
- Test: `tests/callgraph-worker.test.ts`

- [ ] **Step 1: Write failing callgraph worker fixture test**

Create `tests/callgraph-worker.test.ts`. The test compiles a small Java fixture jar, runs the callgraph worker through TypeScript, builds the SQLite database, and proves `findCallers()` / `findCallees()` match the current `mc_find_refs` needs:

```ts
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { spawnSync } from 'child_process';
import { generateCallgraphFromJarForTest, findCallers, findCallees } from '../src/callgraph/index.js';

describe('Java callgraph worker', () => {
  const tempDir = path.join(os.tmpdir(), `mcdev-callgraph-worker-${Date.now()}`);

  beforeEach(() => fs.mkdirSync(tempDir, { recursive: true }));
  afterEach(() => fs.rmSync(tempDir, { recursive: true, force: true }));

  test('builds caller and callee rows for mc_find_refs', async () => {
    const srcDir = path.join(tempDir, 'src', 'fixture');
    const outDir = path.join(tempDir, 'classes');
    fs.mkdirSync(srcDir, { recursive: true });
    fs.mkdirSync(outDir, { recursive: true });
    fs.writeFileSync(path.join(srcDir, 'Foo.java'), `
package fixture;
public class Foo {
    public void run() {
        Bar bar = new Bar();
        bar.tick(3);
        Baz.make();
    }
}
`);
    fs.writeFileSync(path.join(srcDir, 'Bar.java'), `
package fixture;
public class Bar {
    public void tick(int value) {}
}
`);
    fs.writeFileSync(path.join(srcDir, 'Baz.java'), `
package fixture;
public class Baz {
    public static void make() {}
}
`);
    expect(spawnSync('javac', ['--release', '25', '-d', outDir, path.join(srcDir, 'Foo.java'), path.join(srcDir, 'Bar.java'), path.join(srcDir, 'Baz.java')], { stdio: 'inherit' }).status).toBe(0);
    const jarPath = path.join(tempDir, 'fixture.jar');
    expect(spawnSync('jar', ['--create', '--file', jarPath, '-C', outDir, '.'], { stdio: 'inherit' }).status).toBe(0);

    const version = `callgraph-worker-${Date.now()}`;
    const count = await generateCallgraphFromJarForTest(version, jarPath);
    expect(count).toBeGreaterThanOrEqual(2);

    await expect(findCallees(version, 'fixture.Foo', 'run')).resolves.toEqual(
      expect.arrayContaining([
        expect.objectContaining({ className: 'fixture.Bar', methodName: 'tick' }),
        expect.objectContaining({ className: 'fixture.Baz', methodName: 'make' }),
      ])
    );
    await expect(findCallers(version, 'fixture.Bar', 'tick')).resolves.toEqual([
      expect.objectContaining({ className: 'fixture.Foo', methodName: 'run' }),
    ]);
  });
});
```

- [ ] **Step 2: Run test and verify current behavior**

Run:

```bash
npm test -- --runInBand tests/callgraph-worker.test.ts
```

Expected: failure because the callgraph worker and `generateCallgraphFromJarForTest()` do not exist.

- [ ] **Step 3: Implement Java callgraph worker**

Create `src/callgraph/java-worker/CallgraphWorker.java`:

- Accept JSONL requests with `{ id, jarPath }`.
- Iterate `.class` entries in the jar.
- Parse class bytes with `ClassFile.of().parse(bytes)`.
- For each `MethodModel`, inspect `CodeModel` elements.
- For each `InvokeInstruction`, emit an edge:

```json
{
  "callerClass": "fixture.Foo",
  "callerMethod": "run",
  "callerDesc": "()V",
  "calleeClass": "fixture.Bar",
  "calleeMethod": "tick",
  "calleeDesc": "(I)V",
  "lineNumber": 5
}
```

Class names must use dot notation because `findCallers()` and `findCallees()` receive fully qualified class names from users.

- [ ] **Step 4: Implement TypeScript wrapper and DB writer**

Create `src/callgraph/java-worker.ts` with `generateCallEdges(jarPath: string): AsyncIterable<CallEdge>` or `Promise<CallEdge[]>` for the first implementation.

In `src/callgraph/index.ts`:

- Remove `ensureJavaCG()`, `getJavaCGDir()`, `getJavaCGJarPath()`, and `getJavaCGLibDir()` from the main generation flow.
- Keep `ensureRemappedJar()` because mapped 1.x jars still need an unobfuscated/remapped jar.
- Change `generateCallgraph(version)` so it runs the Java callgraph worker against the remapped jar and returns the number of inserted call edges, rather than returning a java-callgraph2 `method_call.txt`.
- Replace `parseCallgraphAndCreateDb(version, callgraphFile)` with `createCallgraphDb(version, edges)` or a streaming equivalent.
- Create the `calls` table before inserts.
- Insert batches into sql.js.
- Create `idx_callee` and `idx_caller` after all inserts.
- Export `generateCallgraphFromJarForTest(version, jarPath)` for the fixture test.

The SQLite schema must remain:

```sql
CREATE TABLE calls (
  id INTEGER PRIMARY KEY,
  caller_class TEXT,
  caller_method TEXT,
  caller_desc TEXT,
  callee_class TEXT,
  callee_method TEXT,
  callee_desc TEXT,
  line_number INTEGER
);
CREATE INDEX idx_callee ON calls(callee_class, callee_method);
CREATE INDEX idx_caller ON calls(caller_class, caller_method);
```

- [ ] **Step 5: Run callgraph test**

Run:

```bash
npm test -- --runInBand tests/callgraph-worker.test.ts
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/callgraph/index.ts src/callgraph/java-worker.ts src/callgraph/java-worker/CallgraphWorker.java tests/callgraph-worker.test.ts
git commit -m "feat: replace java-callgraph2 with Java callgraph worker"
```

---

### Task 7: Stale Manifest Warnings and Docs

**Files:**
- Modify: `src/storage/source-store.ts`
- Modify: `README.md`
- Test: existing test suite

- [ ] **Step 1: Update stale warning copy**

In `src/storage/source-store.ts`, keep the comparison but adjust copy:

```ts
`  This is fine — existing indices still work — but the Java parser ` +
`would produce a more accurate index. Run \`mcdev-mcp rebuild -v ${this.version}\` ` +
`(or \`init -v ${this.version}\` for a full re-fetch) to refresh.\n` +
`  Set MCDEV_SUPPRESS_INDEXER_HINT=1 to silence this message.`
```

Ensure old `manifest.indexerVersion === 'ast'` triggers the stale warning when running backend is `java`.

- [ ] **Step 2: Update README parser documentation**

Replace the AST preview section with:

```md
### Java-based symbol indexer

The default symbol indexer uses a Java worker backed by JDK compiler APIs. It parses Java sources structurally instead of relying on regexes, so records, interfaces, constants, default methods, nested generics, and multi-line declarations are indexed from Java syntax trees.

Regex parsing is still available as an explicit legacy/debug mode:

```bash
MCDEV_INDEXER=regex npx mcdev-mcp rebuild -v 1.21.11
```

Java indexing requires Java 25+ and a JDK with compiler APIs available.
```

Replace the final sentence with:

```md
Java indexing and callgraph generation require Java 25+ and a JDK with compiler APIs and the Class-File API available.
```

Also update the callgraph section to say `mcdev-mcp` generates `mc_find_refs` data with its bundled Java worker, not by cloning/building java-callgraph2 at runtime.

- [ ] **Step 3: Run docs/reference search**

Run:

```bash
rg -n "AST-based Java indexer|MCDEV_AST_PARSER|java-parser-backed|falls back to the regex parser" README.md docs src tests
```

Expected: no stale user-facing AST instructions remain.

- [ ] **Step 4: Commit**

```bash
git add src/storage/source-store.ts README.md
git commit -m "docs: document Java symbol indexer"
```

---

### Task 8: Full Verification

**Files:**
- All touched files

- [ ] **Step 1: Run typecheck**

Run:

```bash
npm run typecheck
```

Expected: PASS.

- [ ] **Step 2: Run lint**

Run:

```bash
npm run lint
```

Expected: PASS.

- [ ] **Step 3: Run full tests**

Run:

```bash
npm test -- --runInBand
```

Expected: PASS.

- [ ] **Step 4: Run build**

Run:

```bash
npm run build
```

Expected: PASS and Java worker class exists under `dist/indexer/java-worker/`.

- [ ] **Step 5: Run live sample rebuild**

If local decompiled sources exist, run a small build-index smoke through tests or CLI. Prefer a temp fixture test over rebuilding the user's full cache. The smoke must prove:

```text
manifest.indexerVersion === "java"
interface constants are indexed
no regex fallback path is reachable for java backend failures
```

- [ ] **Step 6: Completion audit**

Check objective requirements:

```text
Current TypeScript AST indexer replaced: parser-ast.ts and parse-worker.ts removed.
Pure Java worker exists and is compiled in build.
Java is default backend: getParserBackend() returns "java" when env is unset.
Regex is explicit legacy only: only MCDEV_INDEXER=regex selects it.
No Java-to-regex fallback: rg confirms Java worker/index path never calls regex fallback.
Java 25 minimum enforced for indexer and callgraph tooling.
java-callgraph2 runtime clone/build removed from the callgraph path.
Java callgraph worker satisfies mc_find_refs callers/callees queries against a fixture jar.
Callgraph DB creation uses worker edges and creates indexes after bulk insert.
Verification passed: typecheck, lint, tests, build.
```

- [ ] **Step 7: Commit any final fixes**

```bash
git status --short
git add <final files>
git commit -m "test: verify Java indexer backend"
```
