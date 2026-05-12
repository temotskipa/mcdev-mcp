import { sourceStore } from '../../storage/index.js';
import {
  getEffectiveVersion,
  ensureSourceStoreVersion,
  normalizeLimit,
  formatTruncationNote,
  limitSchema,
  LIMIT_DEFAULTS,
} from './helpers.js';

export const mcListClassesTool = {
  name: 'mc_list_classes',
  description: `List all classes under a specific package path. Returns class names and their source locations. Use this to discover classes in a package hierarchy.

Results are capped (default ${LIMIT_DEFAULTS.list_classes.default}, max ${LIMIT_DEFAULTS.list_classes.max}). Pass \`limit\` to widen.`,
  inputSchema: {
    type: 'object' as const,
    properties: {
      packagePath: {
        type: 'string',
        description: 'Package path to list classes from (e.g., "net.minecraft.client", "net.minecraft.world.entity"). Matches exact package and all subpackages.',
      },
      limit: limitSchema('list_classes'),
      version: {
        type: 'string',
        description: 'Optional: Minecraft version to use (e.g., "1.21.1"). If not provided, uses the active version set by mc_version.',
      },
    },
    required: ['packagePath'],
  },

  handler: async (args: { packagePath: string; limit?: number; version?: string }) => {
    const { version, error } = getEffectiveVersion(args.version);
    if (error) {
      return { content: [{ type: 'text' as const, text: error }] };
    }

    ensureSourceStoreVersion(version);

    const { limit, capped } = normalizeLimit(args.limit, 'list_classes');
    const { results, truncated } = sourceStore.listClasses(args.packagePath, limit);

    if (results.length === 0) {
      return {
        content: [{
          type: 'text' as const,
          text: `No classes found under package "${args.packagePath}"`,
        }],
      };
    }

    const output = results.map(r => r.className).join('\n');
    const note = formatTruncationNote({
      shown: results.length,
      total: results.length,
      truncated,
      limit,
      capped,
      noun: 'class(es)',
    });

    return {
      content: [{
        type: 'text' as const,
        text: `Classes under "${args.packagePath}":\n${output}${note}`,
      }],
    };
  },
};
