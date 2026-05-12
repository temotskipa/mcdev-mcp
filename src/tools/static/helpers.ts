import { versionManager } from '../../version-manager.js';
import { sourceStore } from '../../storage/index.js';
import {
  isVersionIndexed,
  getMinecraftSourceDir
} from '../../utils/paths.js';
import * as fs from 'fs';

export function getEffectiveVersion(explicitVersion?: string): { version: string; error?: string } {
  if (explicitVersion) {
    const sourceDir = getMinecraftSourceDir(explicitVersion);
    if (!fs.existsSync(sourceDir)) {
      return {
        version: '',
        error: `Version ${explicitVersion} not initialized. STOP and ask the USER to run this command in their terminal:\n  node dist/cli.js init -v ${explicitVersion}\n\nThis will download, decompile, and index Minecraft ${explicitVersion} sources (including callgraph).`
      };
    }
    if (!isVersionIndexed(explicitVersion)) {
      return {
        version: '',
        error: `Version ${explicitVersion} not indexed. STOP and ask the USER to run this command in their terminal:\n  node dist/cli.js init -v ${explicitVersion}\n\nThis will index Minecraft ${explicitVersion} sources (including callgraph).`
      };
    }
    return { version: explicitVersion };
  }

  const activeVersion = versionManager.getVersion();
  if (!activeVersion) {
    return {
      version: '',
      error: `No Minecraft version is currently set.

STOP and ask the USER which version they want to use, then call mc_version with action="set".
Or, provide a 'version' parameter in your tool call.

To see available versions, call mc_version with action="list".`
    };
  }

  return { version: activeVersion };
}

/**
 * Ensure `sourceStore` is pointed at the given version before a tool call.
 *
 * **Mutates `sourceStore` as a deliberate side effect.** Static tools follow
 * a "set then query" contract: `getEffectiveVersion()` resolves the version,
 * this guard syncs the singleton, and the actual tool method then issues
 * queries without re-checking. The mutation is intentional, not an oversight
 * — the alternative (passing `version` through every storage method) duplicates
 * state and made it easy to forget version-routing in old callers.
 *
 * No return value because callers don't branch on whether a switch happened;
 * the only postcondition that matters is "after this returns, `sourceStore`
 * reflects `version`". `sourceStore.setVersion` is itself idempotent.
 */
export function ensureSourceStoreVersion(version: string): void {
  if (!sourceStore.getVersion() || sourceStore.getVersion() !== version) {
    sourceStore.setVersion(version);
  }
}

// ---------------------------------------------------------------------------
// Pagination / limit helpers
//
// All static tools accept a `limit` parameter and return at most that many
// rows, with a clear truncation note in the text body when more were
// available. Defaults preserve the limits the tools used before this change.
// Hard ceilings prevent runaway tool output that could blow the agent's
// context window.
// ---------------------------------------------------------------------------

export const LIMIT_DEFAULTS = {
  search:        { default:  50, max: 1000 },
  list_classes:  { default: 200, max: 5000 },
  list_packages: { default: 500, max: 5000 },
  find_hierarchy:{ default: 200, max: 5000 },
  find_refs:     { default: 100, max: 5000 },
} as const;

export type LimitKey = keyof typeof LIMIT_DEFAULTS;

/**
 * Normalize a user-supplied `limit` against the tool's default and ceiling.
 * Non-positive values fall back to the default. Values larger than the
 * ceiling are capped (and the caller can surface this in the response).
 */
export function normalizeLimit(
  raw: number | undefined,
  key: LimitKey,
): { limit: number; capped: boolean; defaulted: boolean } {
  const { default: def, max } = LIMIT_DEFAULTS[key];
  if (raw === undefined || raw === null) {
    return { limit: def, capped: false, defaulted: true };
  }
  if (!Number.isFinite(raw) || raw <= 0) {
    return { limit: def, capped: false, defaulted: true };
  }
  const floored = Math.floor(raw);
  if (floored > max) {
    return { limit: max, capped: true, defaulted: false };
  }
  return { limit: floored, capped: false, defaulted: false };
}

/**
 * Standard truncation suffix appended to a tool's text body when results
 * were trimmed. `total` is the actual count produced (or, for early-exit
 * paths, an under-count flagged via `truncated=true`).
 */
export function formatTruncationNote(args: {
  shown: number;
  total: number;
  truncated: boolean;
  limit: number;
  capped: boolean;
  noun: string;
}): string {
  const { shown, total, truncated, limit, capped, noun } = args;
  if (!truncated) {
    return `\nTotal: ${total} ${noun}`;
  }
  const cappedNote = capped ? ` (limit was capped to ${limit} by the server)` : '';
  // When the source layer early-exits, `total` is the early-exit point and
  // we honestly don't know the true total — phrase the message accordingly.
  const more = total > shown ? `${total - shown}+ more` : 'possibly more';
  return `\n... and ${more} ${noun} (showing first ${shown}; pass a larger \`limit\` to see more)${cappedNote}`;
}

/**
 * JSON-Schema fragment for the `limit` parameter, ready to drop into a
 * tool's `inputSchema.properties`.
 */
export function limitSchema(key: LimitKey): {
  type: 'number';
  description: string;
  minimum: number;
} {
  const { default: def, max } = LIMIT_DEFAULTS[key];
  return {
    type: 'number',
    description: `Optional: max results to return (default ${def}, ceiling ${max}). Non-positive or non-finite values fall back to the default.`,
    minimum: 1,
  };
}
