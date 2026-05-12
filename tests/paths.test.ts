import { getHomeDir, getCacheDir, getIndexDir, getToolsDir, ensureDir } from '../src/utils/paths.js';
import * as path from 'path';
import * as os from 'os';
import * as fs from 'fs';

describe('Paths utilities', () => {
  test('getHomeDir lives under the OS-standard cache dir, not a dotfile', () => {
    const home = getHomeDir();
    expect(home).toContain('mcdev-mcp');
    // The legacy `~/.mcdev-mcp/` location was abandoned in favour of env-paths;
    // make sure we don't regress to that.
    expect(home).not.toMatch(/\/\.mcdev-mcp$/);
    // A few sanity-check locations the home dir should sit under, depending
    // on the OS (XDG-friendly on Linux, Library/Caches on macOS, etc.).
    if (process.platform === 'darwin') {
      expect(home).toMatch(/[/\\]Library[/\\]Caches[/\\]mcdev-mcp$/);
    } else if (process.platform === 'linux') {
      // ~/.cache/mcdev-mcp under XDG defaults.
      expect(home).toMatch(/[/\\]\.cache[/\\]mcdev-mcp$/);
    } else if (process.platform === 'win32') {
      // %LOCALAPPDATA%\mcdev-mcp\Cache
      expect(home).toMatch(/[/\\]mcdev-mcp[/\\]Cache$/i);
    }
  });

  test('getCacheDir/getIndexDir/getToolsDir are siblings of getHomeDir', () => {
    const home = getHomeDir();
    expect(getCacheDir()).toBe(path.join(home, 'cache'));
    expect(getIndexDir()).toBe(path.join(home, 'index'));
    expect(getToolsDir()).toBe(path.join(home, 'tools'));
  });
});

describe('ensureDir', () => {
  // os.tmpdir() is portable across macOS / Linux / Windows. The previous
  // hardcoded `/tmp` failed on Windows runners and any *nix host with a
  // non-default temp location.
  let testDir: string;

  beforeEach(() => {
    testDir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcdev-paths-test-'));
    // mkdtempSync already created the dir; the tests below want a fresh
    // unused path inside it.
    testDir = path.join(testDir, 'subject');
  });

  afterEach(() => {
    const parent = path.dirname(testDir);
    if (fs.existsSync(parent)) {
      fs.rmSync(parent, { recursive: true, force: true });
    }
  });

  test('creates directory if it does not exist', () => {
    expect(fs.existsSync(testDir)).toBe(false);
    ensureDir(testDir);
    expect(fs.existsSync(testDir)).toBe(true);
    expect(fs.statSync(testDir).isDirectory()).toBe(true);
  });

  test('does not fail if directory already exists', () => {
    fs.mkdirSync(testDir, { recursive: true });
    expect(() => ensureDir(testDir)).not.toThrow();
    // Idempotent — second call is also a no-op.
    expect(() => ensureDir(testDir)).not.toThrow();
    expect(fs.statSync(testDir).isDirectory()).toBe(true);
  });

  test('creates nested directories', () => {
    const nestedDir = path.join(testDir, 'a', 'b', 'c');
    ensureDir(nestedDir);
    expect(fs.statSync(nestedDir).isDirectory()).toBe(true);
    // Intermediate dirs were created too.
    expect(fs.statSync(path.join(testDir, 'a')).isDirectory()).toBe(true);
    expect(fs.statSync(path.join(testDir, 'a', 'b')).isDirectory()).toBe(true);
  });
});
