import { sourceStore } from '../../storage/index.js';
import {
  getEffectiveVersion,
  ensureSourceStoreVersion,
  normalizeLimit,
  formatTruncationNote,
  limitSchema,
  LIMIT_DEFAULTS,
} from './helpers.js';

export const mcListPackagesTool = {
  name: 'mc_list_packages',
  description: `List all available packages. Optionally filter by namespace (minecraft or fabric). Use this to discover package structure.

Results are capped (default ${LIMIT_DEFAULTS.list_packages.default}, max ${LIMIT_DEFAULTS.list_packages.max}). Pass \`limit\` to widen.`,
  inputSchema: {
    type: 'object' as const,
    properties: {
      namespace: {
        type: 'string',
        enum: ['minecraft', 'fabric'],
        description: 'Optional: filter by namespace (minecraft or fabric)',
      },
      limit: limitSchema('list_packages'),
      version: {
        type: 'string',
        description: 'Optional: Minecraft version to use (e.g., "1.21.1"). If not provided, uses the active version set by mc_version.',
      },
    },
    required: [],
  },

  handler: async (args: { namespace?: 'minecraft' | 'fabric'; limit?: number; version?: string }) => {
    const { version, error } = getEffectiveVersion(args.version);
    if (error) {
      return { content: [{ type: 'text' as const, text: error }] };
    }

    ensureSourceStoreVersion(version);

    const { limit, capped } = normalizeLimit(args.limit, 'list_packages');
    const all = sourceStore.listPackages(args.namespace);

    if (all.length === 0) {
      return {
        content: [{
          type: 'text' as const,
          text: 'No packages found',
        }],
      };
    }

    // Slice locally — listPackages returns a small in-memory string array, no I/O.
    const truncated = all.length > limit;
    const shown = truncated ? all.slice(0, limit) : all;
    const note = formatTruncationNote({
      shown: shown.length,
      total: all.length,
      truncated,
      limit,
      capped,
      noun: 'package(s)',
    });

    return {
      content: [{
        type: 'text' as const,
        text: `Found ${all.length} package(s):\n${shown.join('\n')}${note}`,
      }],
    };
  },
};
