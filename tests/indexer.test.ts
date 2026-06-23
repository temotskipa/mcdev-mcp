import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { buildIndex, loadPackageIndex } from '../src/indexer/index.js';
import { getVersionedIndexManifestPath } from '../src/utils/paths.js';
import { readJsonFileOrNull } from '../src/utils/json-file.js';
import type { IndexManifest } from '../src/utils/types.js';

describe('Index Builder', () => {
  const tempDir = path.join(os.tmpdir(), 'mcdev-mcp-test-' + Date.now());
  
  beforeEach(() => {
    if (!fs.existsSync(tempDir)) {
      fs.mkdirSync(tempDir, { recursive: true });
    }
  });
  
  afterEach(() => {
    if (fs.existsSync(tempDir)) {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });
  
  test('builds index from test sources', async () => {
    const testPackageDir = path.join(tempDir, 'net', 'minecraft', 'test');
    fs.mkdirSync(testPackageDir, { recursive: true });
    
    const javaCode = `
package net.minecraft.test;

public class TestClass extends BaseClass implements TestInterface {
    public static final int CONSTANT = 1;
    private String name;
    
    public void doSomething() {
    }
    
    public int calculate(int a, int b) {
        return a + b;
    }
}
`;
    
    fs.writeFileSync(path.join(testPackageDir, 'TestClass.java'), javaCode);
    
    const indexDir = path.join(tempDir, 'index');
    const manifestPath = path.join(indexDir, 'manifest.json');
    
    const result = await buildIndex({
      minecraftSourceDir: tempDir,
      fabricApiSourceDir: null,
      minecraftVersion: '1.0.0-test',
      fabricApiVersion: null,
    });
    
    expect(result.minecraftPackages).toContain('net.minecraft.test');
    expect(result.totalClasses).toBeGreaterThan(0);
  });
});

describe('AST parser large corpus', () => {
  const ORIG = process.env.MCDEV_AST_PARSER;
  const tempDir = path.join(os.tmpdir(), 'mcdev-mcp-ast-corpus-' + Date.now());

  beforeEach(() => {
    process.env.MCDEV_AST_PARSER = '1';
    if (!fs.existsSync(tempDir)) {
      fs.mkdirSync(tempDir, { recursive: true });
    }
  });

  afterEach(() => {
    if (ORIG === undefined) delete process.env.MCDEV_AST_PARSER;
    else process.env.MCDEV_AST_PARSER = ORIG;
    if (fs.existsSync(tempDir)) {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  test('builds index from 1000+ synthetic Java files without OOM', async () => {
    const classCount = 1200;
    for (let i = 0; i < classCount; i++) {
      const pkg = `net.minecraft.synth${Math.floor(i / 100)}`;
      const pkgDir = path.join(tempDir, ...pkg.split('.'));
      fs.mkdirSync(pkgDir, { recursive: true });
      const javaCode = `
package ${pkg};

public class SynthClass${i} extends BaseClass implements Runnable {
    public static final int CONSTANT = ${i};
    private String field${i};

    public void method${i}(int a, String b) {
        System.out.println(a + b);
    }

    public int calculate${i}(int x, int y) {
        return x + y;
    }
}
`;
      fs.writeFileSync(path.join(pkgDir, `SynthClass${i}.java`), javaCode);
    }

    const version = 'ast-corpus-test';
    const result = await buildIndex({
      minecraftSourceDir: tempDir,
      fabricApiSourceDir: null,
      minecraftVersion: version,
      fabricApiVersion: null,
    });

    expect(result.totalClasses).toBeGreaterThan(1000);

    const manifest = readJsonFileOrNull<IndexManifest>(
      getVersionedIndexManifestPath(version),
      'test/manifest'
    );
    expect(manifest?.indexerVersion).toBe('ast');
    expect(manifest?.packages.minecraft.length).toBeGreaterThan(0);
  });
});

describe('Package Index Loader', () => {
  test('returns null for non-existent package', () => {
    const result = loadPackageIndex('minecraft', 'non.existent.package');
    expect(result).toBeNull();
  });
});
