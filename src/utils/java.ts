import { spawnSync } from 'node:child_process';

export const MIN_SUPPORTED_JAVA = 25;

export function parseJavaMajorVersion(output: string): number | null {
  const match = output.match(/version\s+"([^"]+)"/i) ?? output.match(/(?:openjdk|java)\s+([0-9][^\s]*)/i);
  const raw = match?.[1];
  if (!raw) return null;

  if (raw.startsWith('1.')) {
    const legacy = Number.parseInt(raw.split('.')[1] ?? '', 10);
    return Number.isFinite(legacy) ? legacy : null;
  }

  const major = Number.parseInt(raw, 10);
  return Number.isFinite(major) ? major : null;
}

export function assertJavaAtLeast(
  javaCommand: string,
  minMajor: number,
  purpose: string,
  versionOutput?: string,
): void {
  const output = versionOutput ?? readJavaVersion(javaCommand);
  const major = parseJavaMajorVersion(output);
  if (major === null || major < minMajor) {
    throw new Error(`${purpose} requires Java ${minMajor}+ with JDK compiler APIs. Found: ${output.trim() || 'unknown Java version'}`);
  }
}

function readJavaVersion(javaCommand: string): string {
  const result = spawnSync(javaCommand, ['-version'], { encoding: 'utf-8' });
  if (result.error) {
    throw new Error(`Failed to run ${javaCommand} -version: ${result.error.message}`);
  }
  return `${result.stderr ?? ''}${result.stdout ?? ''}`;
}
