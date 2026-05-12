import { findCallers, findCallees } from '../../callgraph/query.js';
import { hasCallgraphDb } from '../../callgraph/index.js';
import {
  getEffectiveVersion,
  normalizeLimit,
  formatTruncationNote,
  limitSchema,
  LIMIT_DEFAULTS,
} from './helpers.js';

export const mcFindRefsTool = {
  name: 'mc_find_refs',
  description: `Find callers (who calls this method) or callees (what this method calls) using the callgraph database. Useful for understanding code dependencies.

Results are capped (default ${LIMIT_DEFAULTS.find_refs.default}, max ${LIMIT_DEFAULTS.find_refs.max}). Pass \`limit\` to widen.`,
  inputSchema: {
    type: 'object' as const,
    properties: {
      className: {
        type: 'string',
        description: 'Fully qualified class name (e.g., "net.minecraft.client.MinecraftClient")',
      },
      methodName: {
        type: 'string',
        description: 'Method name to find references for',
      },
      direction: {
        type: 'string',
        enum: ['callers', 'callees'],
        description: 'callers = who calls this method, callees = what this method calls',
      },
      limit: limitSchema('find_refs'),
      version: {
        type: 'string',
        description: 'Optional: Minecraft version to use (e.g., "1.21.1"). If not provided, uses the active version set by mc_version.',
      },
    },
    required: ['className', 'methodName', 'direction'],
  },

  handler: async (args: { className: string; methodName: string; direction: 'callers' | 'callees'; limit?: number; version?: string }) => {
    const { version, error } = getEffectiveVersion(args.version);
    if (error) {
      return { content: [{ type: 'text' as const, text: error }] };
    }

    if (!hasCallgraphDb(version)) {
      return {
        content: [{
          type: 'text' as const,
          text: `Version ${version} does not have callgraph data.

STOP and ask the USER to run this command in their terminal:
  node dist/cli.js callgraph -v ${version}

Or for full reinitialization:
  node dist/cli.js init -v ${version}`,
        }],
      };
    }

    const { limit, capped } = normalizeLimit(args.limit, 'find_refs');

    let results;
    try {
      results = args.direction === 'callers'
        ? await findCallers(version, args.className, args.methodName)
        : await findCallees(version, args.className, args.methodName);
    } catch (e) {
      return {
        content: [{
          type: 'text' as const,
          text: `Failed to query callgraph for ${args.className}#${args.methodName} (${args.direction}): ${e instanceof Error ? e.message : String(e)}`,
        }],
        isError: true,
      };
    }

    if (results.length === 0) {
      return {
        content: [{
          type: 'text' as const,
          text: `No ${args.direction} found for ${args.className}#${args.methodName}`,
        }],
      };
    }

    const truncated = results.length > limit;
    const shown = truncated ? results.slice(0, limit) : results;

    const output = shown
      .map(r => `${r.fullName}${r.lineNumber ? ` (line ${r.lineNumber})` : ''}`)
      .join('\n');

    const note = formatTruncationNote({
      shown: shown.length,
      total: results.length,
      truncated,
      limit,
      capped,
      noun: args.direction,
    });

    return {
      content: [{
        type: 'text' as const,
        text: `Found ${results.length} ${args.direction}:\n${output}${note}`,
      }],
    };
  },
};
