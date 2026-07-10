import { execFileSync } from 'node:child_process';
import { existsSync, rmSync } from 'node:fs';
import { readFile, realpath, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ORACLE_SHA = '7b98bdb4a1d885d588cd141d8eb21e3c5c18b2b6';
export const SCRATCH = '.superpowers/parity/node-oracle';

const currentWorktree = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

function git(...args) {
  return execFileSync('git', args, { cwd: currentWorktree, encoding: 'utf8' }).trim();
}

function parseWorktrees(output) {
  return output.split(/\r?\n\r?\n/).filter(Boolean).map((block) => {
    const entry = {};
    for (const line of block.split(/\r?\n/)) {
      const index = line.indexOf(' ');
      if (index !== -1) entry[line.slice(0, index)] = line.slice(index + 1);
    }
    return entry;
  });
}

function assertScratchIsSafe(scratch) {
  const parity = path.resolve(currentWorktree, '.superpowers', 'parity');
  const resolvedScratch = path.resolve(scratch);
  if (resolvedScratch === parity || !resolvedScratch.startsWith(`${parity}${path.sep}`)) {
    throw new Error(`Refusing to remove scratch outside ${parity}: ${resolvedScratch}`);
  }
}

export async function materializeNodeOracle() {
  const worktrees = parseWorktrees(git('worktree', 'list', '--porcelain'));
  const candidates = worktrees.filter((entry) =>
    entry.branch === 'refs/heads/master' && entry.HEAD === ORACLE_SHA,
  );
  if (candidates.length !== 1) {
    throw new Error(`Expected exactly one clean master worktree at ${ORACLE_SHA}, found ${candidates.length}`);
  }

  const master = await realpath(candidates[0].worktree);
  const current = await realpath(currentWorktree);
  if (master === current) throw new Error('Refusing to use the current worktree as the oracle');
  if (git('-C', master, 'status', '--porcelain', '--untracked-files=all')) {
    throw new Error(`Oracle worktree is dirty: ${master}`);
  }

  const scratch = path.resolve(currentWorktree, SCRATCH);
  assertScratchIsSafe(scratch);
  if (existsSync(scratch)) rmSync(scratch, { recursive: true, force: true });
  execFileSync('git', ['clone', '--local', '--no-hardlinks', master, scratch], {
    cwd: currentWorktree,
    stdio: 'inherit',
  });
  execFileSync('git', ['-C', scratch, 'checkout', '--detach', ORACLE_SHA], { stdio: 'inherit' });
  const packageJsonPath = path.join(scratch, 'package.json');
  const packageJson = JSON.parse(await readFile(packageJsonPath, 'utf8'));
  delete packageJson.allowScripts;
  await writeFile(packageJsonPath, `${JSON.stringify(packageJson, null, 2)}\n`);
  const npmUserConfig = path.join(scratch, '.npmrc');
  await writeFile(npmUserConfig, '\n');
  const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';
  const npmOptions = {
    cwd: scratch,
    stdio: 'inherit',
    shell: process.platform === 'win32',
    env: Object.fromEntries(Object.entries(process.env).filter(([name]) =>
      name.toLowerCase() !== 'npm_config_allow_scripts')),
  };
  npmOptions.env.NPM_CONFIG_USERCONFIG = npmUserConfig;
  execFileSync(npm, ['ci'], npmOptions);
  execFileSync(npm, ['--userconfig', npmUserConfig, 'run', 'build'], npmOptions);
  return scratch;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await materializeNodeOracle();
}
