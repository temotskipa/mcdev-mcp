package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.mcp.tool.ToolBinding;

import java.util.LinkedHashMap;
import java.util.Map;

final class MediaRuntimeToolModule {
    private MediaRuntimeToolModule() {
    }

    static Map<String, ToolBinding<?>> handlers(MediaToolSupport support) {
        var handlers = new LinkedHashMap<String, ToolBinding<?>>();
        handlers.put("mc_screenshot", McScreenshotTool.binding(support));
        handlers.put("mc_record_video", McRecordVideoTool.binding(support));
        handlers.put("mc_get_item_texture", McGetItemTextureTool.binding(support));
        handlers.put("mc_get_entity_item_texture", McGetEntityItemTextureTool.binding(support));
        handlers.put("mc_get_item_texture_by_id", McGetItemTextureByIdTool.binding(support));
        handlers.put("mc_set_entity_glow", McSetEntityGlowTool.binding(support));
        handlers.put("mc_set_block_glow", McSetBlockGlowTool.binding(support));
        handlers.put("mc_clear_block_glow", McClearBlockGlowTool.binding(support));
        return handlers;
    }
}
