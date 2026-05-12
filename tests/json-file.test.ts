import { afterEach, beforeEach, describe, expect, it, jest } from '@jest/globals';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {
  readJsonFile,
  readJsonFileOrNull,
} from '../src/utils/json-file.js';

let workdir: string;
let consoleErrSpy: ReturnType<typeof jest.spyOn>;

beforeEach(() => {
  workdir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcdev-jsonfile-'));
  consoleErrSpy = jest.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
  consoleErrSpy.mockRestore();
  if (fs.existsSync(workdir)) {
    fs.rmSync(workdir, { recursive: true, force: true });
  }
});

function writeFile(name: string, contents: string): string {
  const p = path.join(workdir, name);
  fs.writeFileSync(p, contents, 'utf-8');
  return p;
}

describe('readJsonFile', () => {
  it('returns ok with the parsed value for valid JSON', () => {
    const p = writeFile('manifest.json', JSON.stringify({ minecraftVersion: '1.21.11' }));
    const r = readJsonFile<{ minecraftVersion: string }>(p);
    expect(r.status).toBe('ok');
    if (r.status === 'ok') {
      expect(r.value.minecraftVersion).toBe('1.21.11');
    }
  });

  it('returns missing when the file does not exist', () => {
    const r = readJsonFile(path.join(workdir, 'nope.json'));
    expect(r.status).toBe('missing');
  });

  it('returns corrupt with a SyntaxError for malformed JSON', () => {
    const p = writeFile('bad.json', '{ "minecraftVersion": "1.21.11"');
    const r = readJsonFile(p);
    expect(r.status).toBe('corrupt');
    if (r.status === 'corrupt') {
      expect(r.error).toBeInstanceOf(Error);
      expect(r.error.message.length).toBeGreaterThan(0);
    }
  });

  it('returns corrupt for a directory passed as a file path', () => {
    // readFileSync on a directory throws EISDIR — caller can't recover, so
    // we report that as 'corrupt' (the path claimed to exist but we can't
    // get a value from it).
    const r = readJsonFile(workdir);
    expect(r.status).toBe('corrupt');
  });
});

describe('readJsonFileOrNull', () => {
  it('returns the parsed value for valid JSON without logging', () => {
    const p = writeFile('ok.json', JSON.stringify({ a: 1 }));
    const v = readJsonFileOrNull<{ a: number }>(p);
    expect(v).toEqual({ a: 1 });
    expect(consoleErrSpy).not.toHaveBeenCalled();
  });

  it('returns null silently when the file is missing', () => {
    const v = readJsonFileOrNull(path.join(workdir, 'gone.json'));
    expect(v).toBeNull();
    expect(consoleErrSpy).not.toHaveBeenCalled();
  });

  it('returns null and logs corruption with path + label', () => {
    const p = writeFile('bad.json', '{ "broken": ');
    const v = readJsonFileOrNull(p, 'unit-test/scope');
    expect(v).toBeNull();
    expect(consoleErrSpy).toHaveBeenCalledTimes(1);
    const msg = String(consoleErrSpy.mock.calls[0][0]);
    expect(msg).toContain('[unit-test/scope]');
    expect(msg).toContain(p);
    expect(msg).toContain('Re-run');
  });

  it('uses a default label when none is provided', () => {
    const p = writeFile('bad.json', 'not json at all');
    readJsonFileOrNull(p);
    expect(consoleErrSpy).toHaveBeenCalledTimes(1);
    expect(String(consoleErrSpy.mock.calls[0][0])).toContain('[json-file]');
  });
});
