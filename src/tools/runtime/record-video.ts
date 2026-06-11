import { bridgeSession } from "./session.js";
import { expectShape, requireObject, requireResult } from "./validate-resp.js";

/**
 * The wire wants `"frame"` or a number, but some MCP clients flatten the
 * schema's oneOf and deliver numeric intervals as strings ("80") — which the
 * mod then correctly rejects. Coerce numeric strings back; anything else
 * passes through for the mod's own (self-describing) validation.
 */
export function normalizeRecordInterval(value: "frame" | number | string): "frame" | number | string {
    if (typeof value === "string" && value !== "frame" && value.trim() !== "" && Number.isFinite(Number(value))) {
        return Number(value);
    }
    return value;
}

/**
 * Wall-clock budget for one recording request. The bridge only answers when
 * the capture+encode is done, so the deadline must scale with the recording
 * length ("frame" ≈ one render tick ≈ 17ms at 60Hz) instead of BridgeSession's
 * flat 10s default — a 100-frame × 100ms recording is already ~10s of capture
 * before encoding starts. The generous margin covers encode + a slow client;
 * BridgeSession's 5-minute ceiling still applies on top.
 */
export function recordingDeadlineMs(frames: number, interval: "frame" | number | string | undefined): number {
    const perFrameMs = typeof interval === "number" && interval >= 1 ? interval : 17;
    return Math.round(frames * perFrameMs) + 15000;
}

interface RecordVideoGridResult {
    mode: "grid";
    path: string;
    width: number;
    height: number;
    sizeBytes: number;
    mimeType: string;
    frameCount: number;
    frameWidth: number;
    frameHeight: number;
    gridCols: number;
    gridRows: number;
    captureMs: number;
    intervalMs: number;
    dropped: number;
}

interface RecordVideoFramesResult {
    mode: "frames";
    paths: string[];
    frameWidth: number;
    frameHeight: number;
    mimeType: string;
    frameCount: number;
    captureMs: number;
    intervalMs: number;
    sizeBytes: number;
    dropped: number;
}

export const mcRecordVideoTool = {
    name: "mc_record_video",
    description: `Capture a short burst of the Minecraft client framebuffer for debugging
temporal rendering issues — animation glitches, shader bugs, particles,
sub-tick artifacts that mc_screenshot can't resolve. Use the Read tool to
view the result.

Two output modes:
- "grid" (default): one composed JPEG laid out as a frame grid. Best for
  Claude — the whole recording in a single Read.
- "frames": N separate JPEGs. Use only when you need to inspect individual
  frames closely.

Caps (validated mod-side, request rejected if exceeded):
- frames: 1..300 (≈5 s at 60 Hz)
- interval: "frame" (every render tick) or milliseconds >= 1
- downscale: integer >= 1 (default 2)
- quality: [0.05, 1.0] (default 0.75)

Pick interval deliberately. Default to a numeric ms (50–100 ms is usually
right) — smooth motion, never drops frames. Use "frame" only when sub-tick
detail matters; at that cadence the encoder may fall behind and the
response's "dropped" count tells you how many frames were skipped.

The mod and the MCP server must run on the same machine for the returned
paths to be readable here. Files land under
<gameDir>/debugbridge-recordings/<requestId>/.`,
    inputSchema: {
        type: "object" as const,
        properties: {
            frames: {
                type: "number",
                description: "Number of frames to capture, 1..300. Required.",
            },
            interval: {
                description:
                    "\"frame\" for every render tick (~60 Hz), or milliseconds (number, >= 1). " +
                    "Default \"frame\". Recommended: 50–100 ms unless you specifically need sub-tick detail.",
                oneOf: [
                    { type: "string", enum: ["frame"] },
                    { type: "number", minimum: 1 },
                ],
            },
            output: {
                type: "string",
                enum: ["grid", "frames"],
                description: "\"grid\" (one composed JPEG, default) or \"frames\" (N separate JPEGs).",
            },
            gridCols: {
                type: "number",
                description: "Columns in grid layout. Default ceil(sqrt(frames)). Only used in \"grid\" mode.",
            },
            downscale: {
                type: "number",
                description: "Integer downscale factor. Default 2 (half each axis).",
            },
            quality: {
                type: "number",
                description: "JPEG quality in [0.05, 1.0]. Default 0.75. In \"grid\" mode applies once to the composed image.",
            },
        },
        required: ["frames"],
    },

    handler: async (args: {
        frames: number;
        // Schema says "frame" | number, but clients that flatten the oneOf
        // deliver numeric strings — normalizeRecordInterval repairs those.
        interval?: "frame" | number | string;
        output?: "grid" | "frames";
        gridCols?: number;
        downscale?: number;
        quality?: number;
    }) => {
        try {
            const interval = args.interval !== undefined ? normalizeRecordInterval(args.interval) : undefined;
            const payload: Record<string, unknown> = { frames: args.frames };
            if (interval !== undefined) payload.interval = interval;
            if (args.output !== undefined) payload.output = args.output;
            if (args.gridCols !== undefined) payload.gridCols = args.gridCols;
            if (args.downscale !== undefined) payload.downscale = args.downscale;
            if (args.quality !== undefined) payload.quality = args.quality;

            const resp = await bridgeSession.send(
                "record_video", payload, recordingDeadlineMs(args.frames, interval));
            if (!resp.success) {
                return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
            }

            // The result branches on `mode`, so peek before picking a shape.
            const obj = requireObject(requireResult(resp, "record_video"), "record_video");
            const mode = obj.mode;

            if (mode === "grid") {
                const r = expectShape<RecordVideoGridResult>(resp, "record_video", {
                    string: ["mode", "path", "mimeType"],
                    number: [
                        "width", "height", "sizeBytes",
                        "frameCount", "frameWidth", "frameHeight",
                        "gridCols", "gridRows",
                        "captureMs", "intervalMs", "dropped",
                    ],
                });
                const kb = (r.sizeBytes / 1024).toFixed(1);
                const dropNote = r.dropped > 0 ? `, ${r.dropped} dropped` : "";
                return {
                    content: [{
                        type: "text" as const,
                        text:
                            `${r.path}\n` +
                            `(${r.width}x${r.height} JPEG, ${kb} KB; ` +
                            `${r.frameCount} frames @ ${r.frameWidth}x${r.frameHeight}, ` +
                            `grid ${r.gridCols}x${r.gridRows}; ` +
                            `capture ${r.captureMs}ms, avg ${r.intervalMs.toFixed(1)}ms${dropNote})`,
                    }],
                };
            }

            if (mode === "frames") {
                // expectShape only checks primitives; validate `paths` inline.
                const paths = obj.paths;
                if (!Array.isArray(paths) || !paths.every((p) => typeof p === "string")) {
                    throw new Error(
                        `Bridge 'record_video' returned malformed result: 'paths' should be a string array.`
                    );
                }
                const r = expectShape<RecordVideoFramesResult>(resp, "record_video", {
                    string: ["mode", "mimeType"],
                    number: [
                        "frameWidth", "frameHeight",
                        "frameCount",
                        "captureMs", "intervalMs", "sizeBytes", "dropped",
                    ],
                });
                const kb = (r.sizeBytes / 1024).toFixed(1);
                const dropNote = r.dropped > 0 ? `, ${r.dropped} dropped` : "";
                return {
                    content: [{
                        type: "text" as const,
                        text:
                            `${r.frameCount} frames @ ${r.frameWidth}x${r.frameHeight} JPEG, ` +
                            `${kb} KB total; capture ${r.captureMs}ms, avg ${r.intervalMs.toFixed(1)}ms${dropNote}\n` +
                            paths.join("\n"),
                    }],
                };
            }

            throw new Error(
                `Bridge 'record_video' returned unknown mode: ${typeof mode === "string" ? `'${mode}'` : typeof mode}.`
            );
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
};
