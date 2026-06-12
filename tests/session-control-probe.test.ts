import { afterEach, describe, expect, it } from '@jest/globals';
import { spawn, spawnSync } from 'child_process';
import { WebSocketServer } from 'ws';
import {
    isPortListening,
    isProcessAlive,
    probePort,
    resolveListeningPid,
    waitForClientExit,
} from '../src/tools/runtime/session-control.js';

/**
 * probePort against a real (fake-bridge) WebSocket server. This is the piece
 * that decides which instance answered after a relaunch, so it gets a wire
 * test rather than just type-level coverage.
 */

let server: WebSocketServer | null = null;

function startFakeBridge(reply: (req: { id: string; type: string }) => unknown): Promise<number> {
    return new Promise((resolve) => {
        server = new WebSocketServer({ host: '127.0.0.1', port: 0 }, () => {
            const addr = server!.address();
            resolve(typeof addr === 'object' && addr ? addr.port : 0);
        });
        server.on('connection', (ws) => {
            ws.on('message', (data) => {
                const req = JSON.parse(data.toString()) as { id: string; type: string };
                ws.send(JSON.stringify(reply(req)));
            });
        });
    });
}

afterEach((done) => {
    if (server) {
        server.close(() => { server = null; done(); });
        server.clients.forEach((c) => c.terminate());
    } else {
        done();
    }
});

describe('probePort', () => {
    it('resolves the SessionInfo from a status reply', async () => {
        const port = await startFakeBridge((req) => ({
            id: req.id,
            success: true,
            result: { version: '1.21.11', gameDir: '/mc/dev', sessionControlEnabled: true },
        }));
        const info = await probePort(port);
        expect(info.version).toBe('1.21.11');
        expect(info.sessionControlEnabled).toBe(true);
    });

    it('sends a status request, not something else', async () => {
        let seenType: string | null = null;
        const port = await startFakeBridge((req) => {
            seenType = req.type;
            return { id: req.id, success: true, result: { version: 'x' } };
        });
        await probePort(port);
        expect(seenType).toBe('status');
    });

    it('rejects when the bridge reports failure', async () => {
        const port = await startFakeBridge((req) => ({ id: req.id, success: false, error: 'nope' }));
        await expect(probePort(port)).rejects.toThrow(/status failed.*nope/);
    });

    it('rejects quickly when nothing is listening', async () => {
        // Grab a port that is definitely free by opening and closing a server.
        const port = await startFakeBridge(() => ({}));
        await new Promise<void>((resolve) => server!.close(() => { server = null; resolve(); }));
        await expect(probePort(port, 1000)).rejects.toThrow();
    });
});

describe('isPortListening', () => {
    it('distinguishes a listening port from a closed one', async () => {
        const port = await startFakeBridge((req) => ({ id: req.id, success: true, result: {} }));
        expect(await isPortListening(port)).toBe(true);
        await new Promise<void>((resolve) => server!.close(() => { server = null; resolve(); }));
        expect(await isPortListening(port)).toBe(false);
    });
});

/** Open a server just to learn a port that is then guaranteed free. */
async function freedPort(): Promise<number> {
    const port = await startFakeBridge(() => ({}));
    await new Promise<void>((resolve) => server!.close(() => { server = null; resolve(); }));
    return port;
}

/** A real short-lived process to watch die: node parked on a timer. */
function shortLivedChild(ms: number): number {
    const child = spawn(process.execPath, ['-e', `setTimeout(() => {}, ${ms})`], {
        stdio: 'ignore',
    });
    expect(child.pid).toBeDefined();
    return child.pid!;
}

// The POSIX probe shells out to lsof; skip (rather than fail) on machines
// without it. The Windows probe uses PowerShell, which is always present.
const hasPidProbe = process.platform === 'win32' || !spawnSync('lsof', ['-v']).error;
const itWithPidProbe = hasPidProbe ? it : it.skip;

describe('resolveListeningPid', () => {
    itWithPidProbe('resolves the owner of a listening port (this very process)', async () => {
        const port = await startFakeBridge((req) => ({ id: req.id, success: true, result: {} }));
        expect(await resolveListeningPid(port)).toBe(process.pid);
    });

    itWithPidProbe('returns null when nothing listens on the port', async () => {
        expect(await resolveListeningPid(await freedPort())).toBeNull();
    });
});

describe('isProcessAlive', () => {
    it('sees this very process as alive', () => {
        expect(isProcessAlive(process.pid)).toBe(true);
    });

    it('sees an exited process as dead', () => {
        const result = spawnSync(process.execPath, ['-e', '']);
        expect(result.error).toBeUndefined();
        expect(isProcessAlive(result.pid)).toBe(false);
    });
});

describe('waitForClientExit', () => {
    it('times out on the port phase while the port keeps listening', async () => {
        const port = await startFakeBridge((req) => ({ id: req.id, success: true, result: {} }));
        expect(await waitForClientExit(port, process.pid, 400))
            .toEqual({ state: 'timeout', waitingOn: 'port' });
    });

    it('degrades to port-close-only when no PID was resolved', async () => {
        expect(await waitForClientExit(await freedPort(), null, 2000))
            .toEqual({ state: 'exited', pidConfirmed: false });
    });

    it('confirms exit once the process dies after the port closed', async () => {
        const port = await freedPort();
        const pid = shortLivedChild(400);
        expect(isProcessAlive(pid)).toBe(true);
        expect(await waitForClientExit(port, pid, 5000))
            .toEqual({ state: 'exited', pidConfirmed: true });
    }, 10000);

    it('times out on the process phase when the port closed but the process lives on', async () => {
        // Our own PID stands in for a JVM hung mid-shutdown.
        expect(await waitForClientExit(await freedPort(), process.pid, 600))
            .toEqual({ state: 'timeout', waitingOn: 'process' });
    });
});
