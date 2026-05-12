/**
 * Shared environment-variable helpers.
 *
 * Two paths in the runtime layer (`src/tools/runtime/index.ts` and
 * `src/tools/runtime/execute.ts`) both decide whether to enable an
 * env-gated dev tool by testing the same `1|true` regex against
 * `process.env.MCDEV_*`. Centralising the predicate keeps the truthy
 * vocabulary consistent and makes future additions (e.g. accepting `yes`
 * or `on`) a one-liner instead of an N-site change.
 */

const TRUTHY_RE = /^(1|true)$/i;

/** Returns `true` when `value` looks affirmative ("1" / "true", case-insensitive). */
export function isTruthyEnv(value: string | undefined | null): boolean {
  if (!value) return false;
  return TRUTHY_RE.test(value);
}

/** Convenience: read `process.env[name]` and apply {@link isTruthyEnv}. */
export function isEnvOn(name: string): boolean {
  return isTruthyEnv(process.env[name]);
}
