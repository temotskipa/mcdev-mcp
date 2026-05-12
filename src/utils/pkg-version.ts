import { createRequire } from 'node:module';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Reads the project's `package.json` once and caches the version string.
 *
 * Both `cli.ts` (`--version`) and `index.ts` (the MCP `Server` constructor)
 * used to hardcode `'1.0.0'`, which silently lied to users after every
 * `npm version`. The release-verifier hook only checks that
 * `package.json.version` matches `manifest.json.version`, not the embedded
 * literals, so drift was caught nowhere. A single read here keeps both
 * surfaces honest.
 */
const requireFn = createRequire(import.meta.url);

function locatePackageJson(): string {
  // `dist/utils/pkg-version.js` is the published location; `package.json`
  // lives two levels up. The same `..` count works for the in-tree
  // `src/utils/pkg-version.ts` source-checkout build under `ts-node`/jest.
  const here = path.dirname(fileURLToPath(import.meta.url));
  return path.resolve(here, '..', '..', 'package.json');
}

let cached: string | null = null;

export function getPackageVersion(): string {
  if (cached) return cached;
  try {
    const pkg = requireFn(locatePackageJson()) as { version?: string };
    cached = typeof pkg.version === 'string' && pkg.version.length > 0
      ? pkg.version
      : '0.0.0';
  } catch {
    // Best-effort fallback. Keeps the server bootable if the bundled
    // package.json went missing for any reason — but the lie stays small.
    cached = '0.0.0';
  }
  return cached;
}
