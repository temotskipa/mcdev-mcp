import { bridgeSession } from "./session.js";
import { expectShape, checkBase64Bound } from "./validate-resp.js";

interface TextureResult {
    base64Png: string;
    width: number;
    height: number;
    spriteName: string;
}

export const mcGetItemTextureByIdTool = {
    name: "mc_get_item_texture_by_id",
    description: `Render the default texture for an item registry id (e.g.
"minecraft:diamond_pickaxe") as a PNG you can see directly. No inventory
slot required.`,
    inputSchema: {
        type: "object" as const,
        properties: {
            itemId: { type: "string", description: "Registry id like \"minecraft:diamond\"." },
        },
        required: ["itemId"],
    },

    handler: async (args: { itemId: string }) => {
        try {
            const resp = await bridgeSession.send("getItemTextureById", { itemId: args.itemId });
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }
            const r = expectShape<TextureResult>(resp, "getItemTextureById", {
                string: ["base64Png", "spriteName"],
                number: ["width", "height"],
            });
            checkBase64Bound(r.base64Png, "getItemTextureById");
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
