import { describe, expect, it } from '@jest/globals';
import { normalizeRecordInterval, recordingDeadlineMs } from '../src/tools/runtime/record-video.js';

describe('normalizeRecordInterval', () => {
    it('passes "frame" through untouched', () => {
        expect(normalizeRecordInterval('frame')).toBe('frame');
    });

    it('passes numbers through untouched', () => {
        expect(normalizeRecordInterval(80)).toBe(80);
    });

    it('repairs numeric strings from oneOf-flattening clients', () => {
        // Regression: interval 80 arrived on the wire as "80" and the mod
        // rejected it with INVALID_INPUT (string must be "frame").
        expect(normalizeRecordInterval('80')).toBe(80);
        expect(normalizeRecordInterval('33.4')).toBeCloseTo(33.4);
    });

    it('leaves non-numeric strings for the mod\'s own validation', () => {
        expect(normalizeRecordInterval('fast')).toBe('fast');
        expect(normalizeRecordInterval('')).toBe('');
        expect(normalizeRecordInterval('  ')).toBe('  ');
    });
});

describe('recordingDeadlineMs', () => {
    it('scales with numeric intervals so long recordings are not killed client-side', () => {
        // Regression: 100 frames x 100ms is ~10s of capture alone, which the
        // flat 10s default deadline cut off mid-recording.
        expect(recordingDeadlineMs(100, 100)).toBe(100 * 100 + 15000);
    });

    it('assumes ~60Hz for "frame" interval and for omitted intervals', () => {
        expect(recordingDeadlineMs(300, 'frame')).toBe(300 * 17 + 15000);
        expect(recordingDeadlineMs(9, undefined)).toBe(9 * 17 + 15000);
    });

    it('treats unparseable interval strings like the default tick rate', () => {
        expect(recordingDeadlineMs(10, 'fast')).toBe(10 * 17 + 15000);
    });
});
