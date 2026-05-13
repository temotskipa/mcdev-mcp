# Talking to the Minecraft backend from Python

Use this guide when you, as a coding agent, want to write a **Python script** that
drives a live Minecraft instance directly — bypassing the `mcdev-mcp` MCP tools
and speaking to the DebugBridge mod yourself.

You will almost always prefer the MCP tools (`mc_execute`, `mc_snapshot`, etc.)
in conversational use. Reach for a Python client when you need something an
ad‑hoc tool call can't give you: a long‑running watcher, a batch experiment, a
script the user will run later, integration into a non‑MCP harness, etc.

## What the "backend" actually is

There is no Python runtime inside Minecraft. The MCP server, the Python client,
and any other client all talk to the same thing: the **DebugBridge mod**
(`github.com/weikengchen/debugbridge`) running inside the Minecraft JVM. It
exposes a small WebSocket protocol and evaluates **Lua** on the game side.

So a Python "script that calls the Minecraft backend" is really:

```
Python  ──ws──►  DebugBridge mod (inside Minecraft JVM)  ──►  Lua eval  ──►  Java API
```

Your Python code is the transport. The interesting work is still expressed in
Lua snippets sent through the `execute` request type.

## Wire protocol

Source of truth: [`src/tools/runtime/session.ts`](../src/tools/runtime/session.ts) and
[`src/tools/runtime/types.ts`](../src/tools/runtime/types.ts). Re‑read those if
anything below looks stale.

- **Transport:** plain WebSocket, `ws://127.0.0.1:<port>`, no TLS, no auth. The
  bridge only binds to loopback.
- **Default port:** `9876`. The MCP server scans `9876..9885` because users
  routinely run multiple Minecraft instances. Honour `DEBUGBRIDGE_PORT` if it
  is set in your environment.
- **Framing:** one JSON object per WebSocket text frame. Requests and responses
  are correlated by an `id` field that you assign.

### Request

```json
{
  "id":   "req_1",
  "type": "execute" | "snapshot" | "screenshot" | "search" | "runCommand" | "status",
  "payload": { ... }
}
```

Notes per type:

| `type`       | `payload`                                                                  | Returns in `result` |
|--------------|----------------------------------------------------------------------------|---------------------|
| `status`     | `{}`                                                                       | `SessionInfo` (version, mappingStatus, gameDir, logsDir, latestLog, …) |
| `execute`    | `{ "code": "<lua>", "timeoutMs"?: <int 1000-300000> }`                      | Whatever the Lua `return`s; `output` carries `print()` lines |
| `snapshot`   | `{}` (player/world snapshot — see `mc_snapshot`)                            | snapshot JSON |
| `screenshot` | `{}` (returns base64 JPEG)                                                  | image payload |
| `search`     | `{ "pattern": "<str>" }`                                                    | mapping search results |
| `runCommand` | `{ "command": "/give @s diamond" }` — gated by `runCommandEnabled` on the mod | command result |

`runCommand` is opt-in on the mod side; expect `success: false` with an
`error` mentioning the flag if it is disabled.

### Response

```json
{
  "id":      "req_1",
  "success": true,
  "result":  ...,         // arbitrary JSON; absent on errors
  "output":  "...",       // optional, Lua print() captures
  "error":   "..."        // only when success is false
}
```

The server may also push frames whose `id` does not match any outstanding
request (late replies, replies from before a reconnect). Drop them and move
on — that's what the TypeScript client does.

### Timeouts

`timeoutMs` on an `execute` payload bounds Lua execution **inside** the JVM.
You should also enforce a wall‑clock timeout on the WebSocket round‑trip on the
Python side (the TS client adds a +5s grace and a 5‑minute ceiling). Without
both, a frozen game can hang your script indefinitely.

## Minimal Python client

`pip install websockets` and you're done — no other deps.

```python
# debugbridge_client.py
import asyncio, itertools, json, os, websockets

DEFAULT_PORT = int(os.environ.get("DEBUGBRIDGE_PORT", "9876"))

class BridgeError(RuntimeError):
    pass

class DebugBridge:
    def __init__(self, port: int = DEFAULT_PORT):
        self.port = port
        self.ws = None
        self._ids = itertools.count(1)
        self._pending: dict[str, asyncio.Future] = {}
        self._reader_task: asyncio.Task | None = None

    async def connect(self):
        # The MCP server scans 9876..9885; mirror that if the configured port is busy.
        last_err = None
        for port in range(self.port, self.port + 10):
            try:
                self.ws = await asyncio.wait_for(
                    websockets.connect(f"ws://127.0.0.1:{port}"),
                    timeout=2.0,
                )
                self.port = port
                break
            except (OSError, asyncio.TimeoutError) as e:
                last_err = e
        else:
            raise BridgeError(
                f"No DebugBridge on ports {self.port}-{self.port+9}: {last_err}"
            )
        self._reader_task = asyncio.create_task(self._reader())
        return await self.send("status", {})

    async def _reader(self):
        try:
            async for raw in self.ws:
                try:
                    msg = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                fut = self._pending.pop(msg.get("id"), None)
                if fut and not fut.done():
                    fut.set_result(msg)
        except websockets.ConnectionClosed:
            for fut in self._pending.values():
                if not fut.done():
                    fut.set_exception(BridgeError("connection closed"))
            self._pending.clear()

    async def send(self, type_: str, payload: dict, timeout: float = 10.0):
        if self.ws is None:
            raise BridgeError("not connected — call connect() first")
        req_id = f"py_{next(self._ids)}"
        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        self._pending[req_id] = fut
        await self.ws.send(json.dumps({"id": req_id, "type": type_, "payload": payload}))
        try:
            resp = await asyncio.wait_for(fut, timeout=timeout)
        except asyncio.TimeoutError:
            self._pending.pop(req_id, None)
            raise BridgeError(f"{type_} timed out after {timeout}s")
        if not resp.get("success"):
            raise BridgeError(resp.get("error") or "unknown bridge error")
        return resp

    async def execute(self, lua: str, timeout_ms: int = 10_000):
        # The bridge bounds Lua inside the JVM; we still set a slightly larger
        # wall-clock timeout on this side so a frozen JVM can't hang us.
        resp = await self.send(
            "execute",
            {"code": lua, "timeoutMs": timeout_ms},
            timeout=(timeout_ms / 1000) + 5,
        )
        return resp.get("result"), resp.get("output", "")

    async def close(self):
        if self.ws is not None:
            await self.ws.close()
        if self._reader_task is not None:
            self._reader_task.cancel()

async def main():
    bridge = DebugBridge()
    info = await bridge.connect()
    print("session:", info["result"])

    result, output = await bridge.execute("""
        local mc = java.import("net.minecraft.client.Minecraft"):getInstance()
        local p  = mc:player()
        return { name = p:getName():getString(), y = p:getY() }
    """)
    print("lua return:", result)
    if output: print("lua print:", output)

    await bridge.close()

if __name__ == "__main__":
    asyncio.run(main())
```

This is intentionally ~70 lines: one socket, one reader task, response
correlation by id, port scan, timeouts. If you need more (auto‑reconnect,
session‑info verification across reconnects, etc.) lift the patterns from
[`src/tools/runtime/session.ts`](../src/tools/runtime/session.ts) — it has all
been thought through there.

## What Lua you can send

The Lua environment exposed by the bridge is documented in the
`mc_execute` tool description in
[`src/tools/runtime/execute.ts`](../src/tools/runtime/execute.ts). Highlights:

- `java.import("net.minecraft.client.Minecraft"):getInstance()` — your entry point.
- `java.new(cls, ...)`, `java.typeof(obj)`, `java.cast(obj, "name")`,
  `java.iter(coll)`, `java.array(coll)`, `java.isNull(obj)`, `java.ref(id)`.
- Reflection: `java.describe(obj)`, `java.methods(obj, filter?)`,
  `java.fields(obj, filter?)`, `java.supers(obj)`, `java.find(pattern, scope?)`.
- Field access is `obj.fieldName`; method calls are `obj:methodName(args)`.
- All names are **Mojang‑mapped** regardless of Minecraft version.
- `io.*` works for file I/O on the game machine. `os` is **not** available;
  use `java.import("java.lang.System"):currentTimeMillis()` for time and
  `:getenv(name)` / `:getProperty(name)` for env.
- Return values must be JSON‑serializable. Convert Java collections via
  `java.array(...)` before returning them.

## Pitfalls

1. **Iterating hundreds of entities or slots in Lua is slow.** The Lua↔Java
   bridge cost adds up per call. If you find yourself doing this from Python,
   write the loop in Lua and `return` a flat table — one round trip, not N.
2. **The bridge only binds to loopback.** A remote Python client cannot reach
   it without an SSH tunnel.
3. **Connection drops on world reload.** Some Minecraft state changes close
   and reopen the WebSocket. Production scripts need reconnect logic. Use the
   `status` reply's `gameDir` to detect "different game instance now" — see
   `expectedGameDir` in `session.ts`.
4. **`runCommand` is dev‑only.** Both this MCP server (`MCDEV_RUN_COMMAND=1`)
   and the mod (`runCommandEnabled` in `BridgeConfig`) have to opt in. A
   Python client cannot enable it remotely.
5. **There is no streaming response.** A long script either completes within
   `timeoutMs` and returns one JSON blob, or it dies. If you need progress,
   have the Lua snippet append to a file and tail that file from Python.

## When NOT to write a Python client

Most of the time you should just call the MCP tools. The Python client is
worth the extra moving parts only if:

- You need to run unattended (cron, CI, headless test rig).
- You're integrating with a non‑MCP system (a notebook, a game server admin
  tool, a different agent harness).
- You're stress‑testing the bridge itself.

For anything you'd do interactively with the user, `mc_execute` + the native
inspection tools are faster, safer, and already handle reconnects.
