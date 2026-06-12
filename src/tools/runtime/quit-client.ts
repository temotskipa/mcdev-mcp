import { bridgeSession } from "./session.js";
import {
    checkSessionControlEnabled,
    resolveListeningPid,
    waitForClientExit,
    DEFAULT_QUIT_TIMEOUT_S,
} from "./session-control.js";

export const mcQuitClientTool = {
    name: "mc_quit_client",
    description: `Gracefully shut down the entire Minecraft client. This tears
down the user's whole play session, including any world or server they're in —
only do it when asked, or as part of a dev loop the user set up (typically the
quit step of build → deploy → quit → launch → mc_wait_for_bridge; see the
mcdev://guides/dev-loop resource).

Fire-and-acknowledge: the WebSocket is expected to drop moments after the ack
(that counts as success). By default the tool then waits for the client to be
truly gone: it resolves the PID listening on the bridge port before quitting,
polls until the port stops listening, then until that process exits — so on
success it's safe to relaunch immediately, even through launchers that track
the instance (Prism silently ignores --launch while the old process lives).
When the PID can't be resolved (no lsof, permissions), it falls back to
port-close-only and the result says so — the JVM can outlive the port by a
few seconds, so in that case confirm the process exited yourself (pgrep /
kill -0) before relaunching. Requires session_control_enabled=true in the
DebugBridge config.`,
    inputSchema: {
        type: "object" as const,
        properties: {
            waitForExit: {
                type: "boolean",
                description: "Wait until the client is actually gone — bridge port closed, " +
                    "then the client process exited (when its PID could be resolved) — before returning. Default true.",
            },
            timeoutSeconds: {
                type: "number",
                description: `How long to wait for the whole shutdown (port close + process exit). Default ${DEFAULT_QUIT_TIMEOUT_S}.`,
            },
        },
        required: [],
    },

    handler: async (args: { waitForExit?: boolean; timeoutSeconds?: number } = {}) => {
        try {
            const disabled = await checkSessionControlEnabled();
            if (disabled) {
                return { content: [{ type: "text" as const, text: disabled }], isError: true };
            }

            // Capture before quitting: the close handler nulls the port. The
            // PID likewise only resolves while the JVM still owns the port.
            const port = bridgeSession.getConnectedPort();
            const wantWait = args.waitForExit !== false && port !== null;
            const pid = wantWait ? await resolveListeningPid(port) : null;

            try {
                const resp = await bridgeSession.send("quit", {});
                if (!resp.success) {
                    // Self-describing bridge error (e.g. session control gated) — pass through.
                    return { content: [{ type: "text" as const, text: `Error: ${resp.error}` }], isError: true };
                }
            } catch (e) {
                const msg = e instanceof Error ? e.message : String(e);
                if (!/connection closed/i.test(msg)) throw e;
                // Socket dropped before the ack — the client is shutting down.
            }
            bridgeSession.disconnect();

            if (!wantWait) {
                return {
                    content: [{
                        type: "text" as const,
                        text: `Quit queued — the client is shutting down. ` +
                            `Use mc_wait_for_bridge after relaunching to reconnect.`,
                    }],
                };
            }

            const timeoutS = args.timeoutSeconds ?? DEFAULT_QUIT_TIMEOUT_S;
            const result = await waitForClientExit(port, pid, timeoutS * 1000);
            if (result.state === "timeout") {
                const text = result.waitingOn === "port"
                    ? `Quit was acknowledged but port ${port} is still listening after ${timeoutS}s. ` +
                        `The game may be stuck on a save/exit prompt — ask the user to close it ` +
                        `manually before relaunching.`
                    : `Port ${port} closed but the client process (PID ${pid}) is still running ` +
                        `after ${timeoutS}s — it's likely still finishing shutdown, or hung. ` +
                        `Wait for it to exit (kill -0 ${pid}) before relaunching.`;
                return { content: [{ type: "text" as const, text }], isError: true };
            }
            const text = result.pidConfirmed
                ? `Client shut down — port ${port} closed and process ${pid} exited. ` +
                    `Safe to relaunch immediately; use mc_wait_for_bridge to reconnect afterwards.`
                : `Client shut down — port ${port} closed. Couldn't resolve the client PID to ` +
                    `also confirm process exit, and the JVM can outlive the port by a few ` +
                    `seconds — if the launcher tracks the instance (Prism ignores --launch ` +
                    `while the old process lives), confirm it exited (pgrep / kill -0) before ` +
                    `relaunching. Use mc_wait_for_bridge to reconnect afterwards.`;
            return { content: [{ type: "text" as const, text }] };
        } catch (e: unknown) {
            const msg = e instanceof Error ? e.message : String(e);
            return { content: [{ type: "text" as const, text: msg }], isError: true };
        }
    }
};
