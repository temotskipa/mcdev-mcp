import { copyFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const source = resolve('java-worker/build/libs/mcdev-java-worker.jar');
const target = resolve('dist/java/mcdev-java-worker.jar');

if (!existsSync(source)) {
  throw new Error(`Java worker jar was not found at ${source}. Run npm run build:java-worker after Gradle builds the worker.`);
}

mkdirSync(dirname(target), { recursive: true });
copyFileSync(source, target);
console.log(`Copied Java worker jar to ${target}`);
