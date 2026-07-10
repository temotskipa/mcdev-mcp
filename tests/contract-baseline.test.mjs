import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const ORACLE_SHA = '7b98bdb4a1d885d588cd141d8eb21e3c5c18b2b6';
const root = new URL('../', import.meta.url);

async function readJson(relativePath) {
  return JSON.parse(await readFile(new URL(relativePath, root), 'utf8'));
}

test('contract oracle is pinned and catalogs include record video', async () => {
  const oracle = await readJson('contracts/node-oracle.json');
  const devSnapshot = await readJson('src/test/resources/contracts/mcp/tools-list-dev.json');
  const productionMetadata = await readJson('src/main/resources/mcp/tools.json');
  const manifest = await readJson('manifest.json');

  const devTools = devSnapshot.result.tools;
  const manifestTools = manifest.tools.map((tool) => tool.name);

  assert.equal(oracle.commit, ORACLE_SHA);
  assert.ok(devTools.map((tool) => tool.name).includes('mc_record_video'));
  assert.deepEqual(
    [...new Set(devTools.map((tool) => tool.name))],
    productionMetadata.map((tool) => tool.name),
  );
  assert.deepEqual(manifestTools, productionMetadata.map((tool) => tool.name));
});
