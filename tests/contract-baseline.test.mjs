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
  const defaultSnapshot = await readJson('src/test/resources/contracts/mcp/tools-list-default.json');
  const devSnapshot = await readJson('src/test/resources/contracts/mcp/tools-list-dev.json');
  const productionMetadata = await readJson('src/main/resources/mcp/tools.json');
  const manifest = await readJson('manifest.json');

  const defaultTools = defaultSnapshot.result.tools;
  const devTools = devSnapshot.result.tools;
  const manifestTools = manifest.tools.map((tool) => tool.name);
  const devOnlyNames = new Set(['mc_script_logs', 'mc_run_command']);
  const devRecordVideo = devTools.find((tool) => tool.name === 'mc_record_video');
  const manifestRecordVideo = manifest.tools.find((tool) => tool.name === 'mc_record_video');

  assert.equal(oracle.commit, ORACLE_SHA);
  assert.deepEqual(
    defaultTools.map((tool) => tool.name),
    devTools.filter((tool) => !devOnlyNames.has(tool.name)).map((tool) => tool.name),
  );
  assert.equal(defaultTools.some((tool) => devOnlyNames.has(tool.name)), false);
  assert.ok(devRecordVideo);
  assert.ok(manifestRecordVideo);
  assert.equal(manifestRecordVideo.description, devRecordVideo.description);
  assert.deepEqual(manifestRecordVideo.inputSchema, devRecordVideo.inputSchema);
  assert.deepEqual(
    [...new Set(devTools.map((tool) => tool.name))],
    productionMetadata.map((tool) => tool.name),
  );
  assert.deepEqual(manifestTools, productionMetadata.map((tool) => tool.name));
});
