import { BridgeResponse } from "./types.js";

/**
 * Validation helpers for the `resp.result` payloads returned by the
 * DebugBridge mod. Every runtime tool used to either `as`-cast the result
 * to its expected shape (which silently produces `'undefinedxundefined'`
 * strings or NaN math when the bridge protocol drifts) or pass it straight
 * to `JSON.stringify` (which prints `'undefined'` for missing results and
 * throws on circular references). These helpers replace both patterns
 * with one structured error per failure mode.
 */

function describe(v: unknown): string {
    if (v === null) return "null";
    if (Array.isArray(v)) return "array";
    return typeof v;
}

/**
 * Confirm the bridge returned a non-null, non-undefined `result`. Caller
 * has already handled `!resp.success` separately; this guards the success
 * branch against a buggy mod that says success but ships no payload.
 */
export function requireResult(resp: BridgeResponse, what: string): unknown {
    if (resp.result === undefined || resp.result === null) {
        throw new Error(
            `Bridge '${what}' reported success but returned no result. ` +
            `This is a bridge protocol mismatch — please verify the DebugBridge mod version.`
        );
    }
    return resp.result;
}

/** Throw if `value` is not a plain object (`null`, primitives, arrays all fail). */
export function requireObject(value: unknown, what: string): Record<string, unknown> {
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
        throw new Error(
            `Bridge '${what}' returned non-object result (got ${describe(value)}). ` +
            `Expected a JSON object.`
        );
    }
    return value as Record<string, unknown>;
}

/**
 * Field-typing schema for {@link expectShape}. Only declares the fields the
 * caller plans to read — the bridge is free to add new ones without breaking
 * us, and missing fields produce a single readable error rather than a chain
 * of TypeErrors at property-access time.
 */
export interface ShapeSpec {
    string?: string[];
    number?: string[];   // must also be `Number.isFinite` (no NaN, no Infinity)
    boolean?: string[];
}

/**
 * Unwrap `resp.result`, confirm it's an object, and check that every field
 * named in `spec` is present with the expected primitive type. Returns the
 * narrowed object; throws a single structured error listing every offending
 * field if any check fails.
 */
export function expectShape<T>(
    resp: BridgeResponse,
    what: string,
    spec: ShapeSpec,
): T {
    const obj = requireObject(requireResult(resp, what), what);
    const errors: string[] = [];

    for (const k of spec.string ?? []) {
        const v = obj[k];
        if (typeof v !== "string") errors.push(`'${k}' should be string, got ${describe(v)}`);
    }
    for (const k of spec.number ?? []) {
        const v = obj[k];
        if (typeof v !== "number" || !Number.isFinite(v)) {
            errors.push(`'${k}' should be a finite number, got ${describe(v)}`);
        }
    }
    for (const k of spec.boolean ?? []) {
        const v = obj[k];
        if (typeof v !== "boolean") errors.push(`'${k}' should be boolean, got ${describe(v)}`);
    }

    if (errors.length > 0) {
        throw new Error(`Bridge '${what}' returned malformed result: ${errors.join("; ")}.`);
    }
    // Constraint-free `T` because TS doesn't see named interfaces as
    // assignable to `Record<string, unknown>` without an explicit index
    // signature. Runtime check above is what makes this safe; the cast
    // through `unknown` documents the trust boundary.
    return obj as unknown as T;
}

/**
 * `JSON.stringify` with a circular-reference replacer — the legacy stringify
 * call sites would throw on a self-referential `result`. Replaces cycles
 * with `'[Circular]'` instead.
 */
export function safeStringify(value: unknown, indent: number = 2): string {
    const seen = new WeakSet<object>();
    return JSON.stringify(
        value,
        (_key, v) => {
            if (typeof v === "object" && v !== null) {
                if (seen.has(v as object)) return "[Circular]";
                seen.add(v as object);
            }
            return v;
        },
        indent,
    );
}

/**
 * Soft cap on the base64 PNG payloads the texture tools forward as MCP
 * image content. ~5 MB of decoded image (≈6.7 MB base64) is well above any
 * legitimate item / icon render, but a runaway response with no bound would
 * blow up the agent's context window.
 */
export const MAX_BASE64_PNG_BYTES = 7 * 1024 * 1024;

export function checkBase64Bound(
    base64: string,
    what: string,
    max: number = MAX_BASE64_PNG_BYTES,
): string {
    if (base64.length > max) {
        const sizeMb = (base64.length / 1024 / 1024).toFixed(1);
        const maxMb = (max / 1024 / 1024).toFixed(1);
        throw new Error(
            `Bridge '${what}' returned a ${sizeMb} MB base64 PNG, exceeding the ${maxMb} MB cap. ` +
            `This usually means a malformed bridge response — please report it.`
        );
    }
    return base64;
}
