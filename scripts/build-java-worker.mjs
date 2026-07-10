import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';

const workerDir = resolve('java-worker');
const isWindows = process.platform === 'win32';
const wrapperPath = resolve(workerDir, isWindows ? 'gradlew.bat' : 'gradlew');
const command = isWindows ? (process.env.ComSpec ?? 'cmd.exe') : './gradlew';
const args = isWindows ? ['/d', '/s', '/c', wrapperPath, 'shadowJar'] : ['shadowJar'];

if (!existsSync(wrapperPath)) {
  throw new Error(`Gradle wrapper was not found at ${wrapperPath}.`);
}

const result = spawnSync(command, args, {
  cwd: workerDir,
  stdio: 'inherit',
});

if (result.error) {
  throw result.error;
}
if (result.status !== 0) {
  process.exit(result.status ?? 1);
}

const copy = spawnSync(process.execPath, ['scripts/copy-java-worker.mjs'], { stdio: 'inherit' });
if (copy.error) {
  throw copy.error;
}
if (copy.status !== 0) {
  process.exit(copy.status ?? 1);
}
