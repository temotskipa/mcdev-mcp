import { spawn } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { materializeNodeOracle } from './materialize-node-oracle.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const scratch = await materializeNodeOracle();
const outputRoot = path.join(root, 'src', 'test', 'resources', 'contracts');

function normalize(value, key = '') {
  if (Array.isArray(value)) return value.map((item) => normalize(item, key));
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([name, item]) => [name, normalize(item, name)]));
  }
  if (key === 'id') return 0;
  if (key === 'timestamp' || key.endsWith('At')) return '<TIMESTAMP>';
  if (key === 'port' || key.endsWith('Port')) return '<EPHEMERAL_PORT>';
  if (typeof value === 'string') {
    const cacheRoots = [path.join(os.homedir(), '.cache'), process.env.APPDATA, process.env.LOCALAPPDATA]
      .filter(Boolean)
      .map((entry) => path.resolve(entry));
    for (const cacheRoot of cacheRoots) {
      if (value.startsWith(cacheRoot)) return `<ABSOLUTE_CACHE_PATH>${value.slice(cacheRoot.length)}`;
    }
  }
  return value;
}

function sendJsonRpc(env, requests, expectedResponses) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ['dist/cli.js', 'serve'], {
      cwd: scratch,
      env: { ...process.env, ...env },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    let buffer = '';
    const responses = [];
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error('Timed out waiting for MCP response'));
    }, 30000);
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      buffer += chunk;
      for (const line of buffer.split(/\r?\n/).slice(0, -1)) {
        if (!line.trim()) continue;
        try { responses.push(JSON.parse(line)); } catch (error) { reject(error); return; }
      }
      buffer = buffer.slice(buffer.lastIndexOf('\n') + 1);
      if (responses.length === expectedResponses) {
        clearTimeout(timer);
        child.kill();
        resolve(responses.map((response) => normalize(response)));
      }
    });
    child.on('error', reject);
    child.stdin.on('error', () => {});
    for (const request of requests) child.stdin.write(`${JSON.stringify(request)}\n`);
  });
}

const initialize = {
  jsonrpc: '2.0', id: 1, method: 'initialize',
  params: { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'node-oracle', version: '1' } },
};
const initialized = { jsonrpc: '2.0', method: 'notifications/initialized', params: {} };

async function captureMcp(env, files, extraRequests) {
  const responses = await sendJsonRpc(env, [initialize, initialized, ...extraRequests], 1 + extraRequests.filter((request) => request.id !== undefined).length);
  for (const [file, responseIndex] of Object.entries(files)) {
    await writeFile(path.join(outputRoot, 'mcp', file), `${JSON.stringify(responses[responseIndex], null, 2)}\n`);
  }
  return responses;
}

await mkdir(path.join(outputRoot, 'mcp'), { recursive: true });
const defaultResponses = await captureMcp({}, { 'initialize.json': 0, 'tools-list-default.json': 1 }, [
  { jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} },
]);
const devResponses = await captureMcp(
  { MCDEV_SCRIPT_LOGS: '1', MCDEV_RUN_COMMAND: '1' },
  { 'tools-list-dev.json': 1 },
  [
    { jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} },
  ],
);
const resourceResponses = await captureMcp({}, {
  'resources-list.json': 1,
  'resource-python-scripting.json': 2,
  'resource-dev-loop.json': 3,
}, [
  { jsonrpc: '2.0', id: 2, method: 'resources/list', params: {} },
  { jsonrpc: '2.0', id: 3, method: 'resources/read', params: { uri: 'mcdev://guides/python-scripting' } },
  { jsonrpc: '2.0', id: 4, method: 'resources/read', params: { uri: 'mcdev://guides/dev-loop' } },
]);

const help = await new Promise((resolve, reject) => {
  const child = spawn(process.execPath, ['dist/cli.js', '--help'], { cwd: scratch });
  let stdout = ''; child.stdout.on('data', (chunk) => { stdout += chunk; });
  child.on('close', (code) => code === 0 ? resolve(stdout) : reject(new Error(`--help exited ${code}`)));
});
const version = await new Promise((resolve, reject) => {
  const child = spawn(process.execPath, ['dist/cli.js', '--version'], { cwd: scratch });
  let stdout = ''; child.stdout.on('data', (chunk) => { stdout += chunk; });
  child.on('close', (code) => code === 0 ? resolve(stdout) : reject(new Error(`--version exited ${code}`)));
});
await mkdir(path.join(outputRoot, 'cli'), { recursive: true });
await writeFile(path.join(outputRoot, 'cli', 'help.txt'), help);
await writeFile(path.join(outputRoot, 'cli', 'version.txt'), version);

const devTools = devResponses[1].result.tools;
await mkdir(path.join(root, 'contracts'), { recursive: true });
await writeFile(path.join(root, 'contracts', 'node-oracle.json'), `${JSON.stringify({
  branch: 'master', commit: '7b98bdb4a1d885d588cd141d8eb21e3c5c18b2b6', capturedAt: '2026-07-10', mutableCheckoutUsed: false,
}, null, 2)}\n`);
await mkdir(path.join(root, 'src', 'main', 'resources', 'mcp'), { recursive: true });
await writeFile(path.join(root, 'src', 'main', 'resources', 'mcp', 'tools.json'), `${JSON.stringify(devTools, null, 2)}\n`);
