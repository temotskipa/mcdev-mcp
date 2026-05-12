import { bridgeSession } from "./session.js";
import { expectShape, checkBase64Bound } from "./validate-resp.js";

interface TextureResult {
    base64Png: string;
    width: number;
    height: number;
    spriteName: string;
}

export const mcGetItemTextureTool = {
    name: "mc_get_item_texture",
    description: `Render the item in the player's inventory slot N as a PNG you can
see directly. Honors damage / CustomModelData resource-pack overrides on
1.21.11; falls back to the baked sprite on 1.19. Returns the rendered PNG
plus a one-line text caption with dimensions + sprite name.`,
    inputSchema: {
        type: "object" as const,
        properties: {
            slot: { type: "number", description: "Inventory slot index (0-35 main inv, 36-39 armor, 40 offhand)." },
        },
        required: ["slot"],
    },

    handler: async (args: { slot: number }) => {
        try {
            const resp = await bridgeSession.send("getItemTexture", { slot: args.slot });
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            const r = expectShape<TextureResult>(resp, "getItemTexture", {
                string: ["base64Png", "spriteName"],
                number: ["width", "height"],
            });
            checkBase64Bound(r.base64Png, "getItemTexture");
            return {
                content: [
                    { type: "image" as const, data: r.base64Png, mimeType: "image/png" },
                    { type: "text" as const, text: `${r.width}x${r.height} sprite=${r.spriteName}` },
                ],
            };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
};
