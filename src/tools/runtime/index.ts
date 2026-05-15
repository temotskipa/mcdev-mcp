export { mcConnectTool } from './connect.js';
export { mcExecuteTool } from './execute.js';
export { mcSnapshotTool } from './snapshot.js';
export { mcScreenshotTool } from './screenshot.js';
export { mcRecordVideoTool } from './record-video.js';
export { mcRunCommandTool } from './command.js';
export { mcScriptLogsTool } from './script-logs.js';
export { mcNearbyEntitiesTool } from './nearby-entities.js';
export { mcEntityDetailsTool } from './entity-details.js';
export { mcNearbyBlocksTool } from './nearby-blocks.js';
export { mcBlockDetailsTool } from './block-details.js';
export { mcLookedAtEntityTool } from './looked-at-entity.js';
export { mcSetEntityGlowTool } from './set-entity-glow.js';
export { mcSetBlockGlowTool } from './set-block-glow.js';
export { mcClearBlockGlowTool } from './clear-block-glow.js';
export { mcGetItemTextureTool } from './get-item-texture.js';
export { mcGetEntityItemTextureTool } from './get-entity-item-texture.js';
export { mcGetItemTextureByIdTool } from './get-item-texture-by-id.js';
export { mcChatHistoryTool } from './chat-history.js';
export { mcScreenInspectTool } from './screen-inspect.js';

import { mcConnectTool } from './connect.js';
import { mcExecuteTool } from './execute.js';
import { mcSnapshotTool } from './snapshot.js';
import { mcScreenshotTool } from './screenshot.js';
import { mcRecordVideoTool } from './record-video.js';
import { mcRunCommandTool } from './command.js';
import { mcScriptLogsTool } from './script-logs.js';
import { mcNearbyEntitiesTool } from './nearby-entities.js';
import { mcEntityDetailsTool } from './entity-details.js';
import { mcNearbyBlocksTool } from './nearby-blocks.js';
import { mcBlockDetailsTool } from './block-details.js';
import { mcLookedAtEntityTool } from './looked-at-entity.js';
import { mcSetEntityGlowTool } from './set-entity-glow.js';
import { mcSetBlockGlowTool } from './set-block-glow.js';
import { mcClearBlockGlowTool } from './clear-block-glow.js';
import { mcGetItemTextureTool } from './get-item-texture.js';
import { mcGetEntityItemTextureTool } from './get-entity-item-texture.js';
import { mcGetItemTextureByIdTool } from './get-item-texture-by-id.js';
import { mcChatHistoryTool } from './chat-history.js';
import { mcScreenInspectTool } from './screen-inspect.js';

// Dev-only tools (default off). The bridge mirrors these gates with its own
// BridgeConfig flags (runCommandEnabled), so even if
// these envs are flipped on, calls only succeed when both sides agree.
import { isEnvOn } from '../../utils/env.js';
const scriptLogsEnabled = isEnvOn('MCDEV_SCRIPT_LOGS');
const runCommandEnabled = isEnvOn('MCDEV_RUN_COMMAND');

export const runtimeTools = [
    mcConnectTool,
    mcExecuteTool,
    mcSnapshotTool,
    mcScreenshotTool,
    mcRecordVideoTool,
    mcNearbyEntitiesTool,
    mcEntityDetailsTool,
    mcNearbyBlocksTool,
    mcBlockDetailsTool,
    mcLookedAtEntityTool,
    mcSetEntityGlowTool,
    mcSetBlockGlowTool,
    mcClearBlockGlowTool,
    mcGetItemTextureTool,
    mcGetEntityItemTextureTool,
    mcGetItemTextureByIdTool,
    mcChatHistoryTool,
    mcScreenInspectTool,
    // Dev-only tools — default off; flip env on both sides to enable.
    ...(scriptLogsEnabled ? [mcScriptLogsTool] : []),
    ...(runCommandEnabled ? [mcRunCommandTool] : []),
];
