import * as fs from 'fs';

/**
 * Discriminated result for a JSON-file read.
 *
 * - `'ok'`      — parse succeeded; `value` holds the result.
 * - `'missing'` — the file does not exist on disk. Caller decides whether
 *                 that is benign (lazy init) or actionable.
 * - `'corrupt'` — the file existed but couldn't be read or parsed. `error`
 *                 carries the underlying `SyntaxError` / `Error`. Treat as
 *                 actionable — the on-disk state has drifted from what the
 *                 indexer wrote, so the safe move is usually to re-init.
 */
export type JsonFileResult<T> =
  | { status: 'ok'; value: T }
  | { status: 'missing' }
  | { status: 'corrupt'; error: Error };

/**
 * Read a JSON file and report missing-vs-corrupt as separate states.
 *
 * Use this when the caller actually wants to distinguish — e.g. surface a
 * "your index is corrupt, re-run init" message to a human instead of the
 * generic "no results" path. For the much more common "I just want the value
 * or null" pattern, prefer {@link readJsonFileOrNull}.
 *
 * Read failures (EACCES, ENOENT race) are treated as `'corrupt'` rather than
 * `'missing'` because from the caller's POV the file *claimed* to exist and
 * we still can't get a value out of it; that's the actionable shape.
 */
export function readJsonFile<T>(filePath: string): JsonFileResult<T> {
  if (!fs.existsSync(filePath)) {
    return { status: 'missing' };
  }
  let raw: string;
  try {
    raw = fs.readFileSync(filePath, 'utf-8');
  } catch (e) {
    return { status: 'corrupt', error: e instanceof Error ? e : new Error(String(e)) };
  }
  try {
    return { status: 'ok', value: JSON.parse(raw) as T };
  } catch (e) {
    return { status: 'corrupt', error: e instanceof Error ? e : new Error(String(e)) };
  }
}

/**
 * Best-effort variant: returns the parsed value, or `null` if the file was
 * missing **or** corrupt. Corruption is logged to stderr with the file path
 * and underlying error so silent index-degradation has at least one
 * breadcrumb in the MCP client logs (where stderr is forwarded).
 *
 * This is the drop-in replacement for the older
 * `try { JSON.parse(fs.readFileSync(...)) } catch { return null }` pattern —
 * same return shape, same null-pun on missing files, just with a diagnostic
 * the previous code swallowed.
 *
 * @param label  short tag used in the log line, e.g. `'source-store/manifest'`.
 *               Helps a reader find which subsystem reported the bad file.
 */
export function readJsonFileOrNull<T>(filePath: string, label = 'json-file'): T | null {
  const r = readJsonFile<T>(filePath);
  if (r.status === 'ok') return r.value;
  if (r.status === 'missing') return null;
  console.error(
    `[${label}] Corrupt JSON at ${filePath}: ${r.error.message}\n` +
    `  Treating as if missing. Re-run \`mcdev-mcp init\` for the affected version to rebuild.`
  );
  return null;
}
