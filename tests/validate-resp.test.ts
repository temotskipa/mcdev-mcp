import { describe, expect, it } from '@jest/globals';
import {
    requireResult,
    requireObject,
    expectShape,
    safeStringify,
    checkBase64Bound,
    MAX_BASE64_PNG_BYTES,
} from '../src/tools/runtime/validate-resp.js';
import type { BridgeResponse } from '../src/tools/runtime/types.js';

const ok = (result: unknown): BridgeResponse => ({ id: 'r', success: true, result });

describe('requireResult', () => {
    it('returns the result when present', () => {
        expect(requireResult(ok({ a: 1 }), 'foo')).toEqual({ a: 1 });
    });

    it('throws a structured error when result is undefined', () => {
        expect(() => requireResult(ok(undefined), 'snapshot')).toThrow(/Bridge 'snapshot' reported success but returned no result/);
    });

    it('throws a structured error when result is null', () => {
        expect(() => requireResult(ok(null), 'snapshot')).toThrow(/no result/);
    });
});

describe('requireObject', () => {
    it('returns the value when it is a plain object', () => {
        const v = { a: 1 };
        expect(requireObject(v, 'x')).toBe(v);
    });

    it('rejects null', () => {
        expect(() => requireObject(null, 'x')).toThrow(/non-object result \(got null\)/);
    });

    it('rejects arrays', () => {
        expect(() => requireObject([1, 2, 3], 'x')).toThrow(/non-object result \(got array\)/);
    });

    it('rejects primitives', () => {
        expect(() => requireObject('hi', 'x')).toThrow(/got string/);
        expect(() => requireObject(42, 'x')).toThrow(/got number/);
        expect(() => requireObject(true, 'x')).toThrow(/got boolean/);
    });
});

describe('expectShape', () => {
    interface Sample {
        path: string;
        sizeBytes: number;
    }

    it('returns the typed object when every required field is well-typed', () => {
        const r = expectShape<Sample>(ok({ path: '/tmp/a.jpg', sizeBytes: 1024 }), 'screenshot', {
            string: ['path'],
            number: ['sizeBytes'],
        });
        expect(r.path).toBe('/tmp/a.jpg');
        expect(r.sizeBytes).toBe(1024);
    });

    it('throws listing every missing or wrong-typed field', () => {
        try {
            expectShape(
                ok({ path: 42, sizeBytes: 'huh' }),
                'screenshot',
                { string: ['path', 'mimeType'], number: ['sizeBytes', 'width'] },
            );
            throw new Error('expected to throw');
        } catch (e) {
            const msg = (e as Error).message;
            expect(msg).toContain("Bridge 'screenshot' returned malformed result");
            expect(msg).toContain("'path' should be string, got number");
            expect(msg).toContain("'mimeType' should be string, got undefined");
            expect(msg).toContain("'sizeBytes' should be a finite number, got string");
            expect(msg).toContain("'width' should be a finite number, got undefined");
        }
    });

    it('rejects NaN / Infinity for number fields', () => {
        expect(() => expectShape(ok({ n: NaN }),       'x', { number: ['n'] })).toThrow(/finite number, got number/);
        expect(() => expectShape(ok({ n: Infinity }),  'x', { number: ['n'] })).toThrow(/finite number, got number/);
    });

    it('propagates requireResult / requireObject errors', () => {
        expect(() => expectShape(ok(undefined), 'x', {})).toThrow(/no result/);
        expect(() => expectShape(ok([1, 2]),    'x', {})).toThrow(/non-object result \(got array\)/);
    });
});

describe('safeStringify', () => {
    it('handles plain objects identically to JSON.stringify', () => {
        const v = { a: 1, b: { c: [2, 3] } };
        expect(safeStringify(v)).toBe(JSON.stringify(v, null, 2));
    });

    it('replaces cycles with "[Circular]" instead of throwing', () => {
        const a: Record<string, unknown> = { name: 'a' };
        a.self = a;
        // Plain JSON.stringify would throw TypeError here.
        expect(() => JSON.stringify(a)).toThrow();
        const out = safeStringify(a);
        expect(out).toContain('"name": "a"');
        expect(out).toContain('"[Circular]"');
    });

    it('honours an explicit indent parameter', () => {
        expect(safeStringify({ a: 1 }, 0)).toBe('{"a":1}');
    });
});

describe('checkBase64Bound', () => {
    it('returns the input unchanged when within the cap', () => {
        const tiny = 'x'.repeat(1024);
        expect(checkBase64Bound(tiny, 'tex')).toBe(tiny);
    });

    it('throws with a readable message when over the cap', () => {
        const oversized = 'x'.repeat(MAX_BASE64_PNG_BYTES + 1);
        expect(() => checkBase64Bound(oversized, 'getItemTexture')).toThrow(
            /Bridge 'getItemTexture' returned a [\d.]+ MB base64 PNG, exceeding the [\d.]+ MB cap/,
        );
    });

    it('respects a custom max', () => {
        expect(() => checkBase64Bound('x'.repeat(2048), 'tex', 1024)).toThrow(/exceeding the/);
    });
});
