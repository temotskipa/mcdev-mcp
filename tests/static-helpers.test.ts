import { describe, expect, it } from '@jest/globals';
import {
  normalizeLimit,
  formatTruncationNote,
  LIMIT_DEFAULTS,
} from '../src/tools/static/helpers.js';

describe('normalizeLimit', () => {
  it('returns the per-tool default when raw is undefined', () => {
    const r = normalizeLimit(undefined, 'search');
    expect(r.limit).toBe(LIMIT_DEFAULTS.search.default);
    expect(r.defaulted).toBe(true);
    expect(r.capped).toBe(false);
  });

  it('returns the default for non-finite or non-positive values', () => {
    expect(normalizeLimit(NaN, 'search').defaulted).toBe(true);
    expect(normalizeLimit(0, 'search').defaulted).toBe(true);
    expect(normalizeLimit(-5, 'search').defaulted).toBe(true);
    expect(normalizeLimit(Infinity, 'search').defaulted).toBe(true);
  });

  it('floors fractional values', () => {
    const r = normalizeLimit(12.7, 'search');
    expect(r.limit).toBe(12);
    expect(r.capped).toBe(false);
    expect(r.defaulted).toBe(false);
  });

  it('caps values that exceed the tool ceiling', () => {
    const r = normalizeLimit(LIMIT_DEFAULTS.search.max + 100, 'search');
    expect(r.limit).toBe(LIMIT_DEFAULTS.search.max);
    expect(r.capped).toBe(true);
    expect(r.defaulted).toBe(false);
  });

  it('respects different ceilings per tool', () => {
    expect(normalizeLimit(10000, 'list_classes').limit).toBe(LIMIT_DEFAULTS.list_classes.max);
    expect(normalizeLimit(10000, 'find_refs').limit).toBe(LIMIT_DEFAULTS.find_refs.max);
    expect(normalizeLimit(10000, 'list_packages').limit).toBe(LIMIT_DEFAULTS.list_packages.max);
    expect(normalizeLimit(10000, 'find_hierarchy').limit).toBe(LIMIT_DEFAULTS.find_hierarchy.max);
  });
});

describe('formatTruncationNote', () => {
  const baseArgs = {
    shown: 50,
    total: 50,
    truncated: false,
    limit: 50,
    capped: false,
    noun: 'result(s)',
  };

  it('shows a Total line when results were not truncated', () => {
    expect(formatTruncationNote(baseArgs)).toBe('\nTotal: 50 result(s)');
  });

  it('flags early-exit truncation with "+more" wording', () => {
    const note = formatTruncationNote({ ...baseArgs, truncated: true, total: 50, shown: 50 });
    // total === shown when the source layer early-exits — the note should be
    // honest that there could be more without claiming a specific number.
    expect(note).toContain('possibly more');
    expect(note).toContain('showing first 50');
    expect(note).toContain('limit');
  });

  it('shows a definite "+N more" when full result count is known', () => {
    const note = formatTruncationNote({ ...baseArgs, truncated: true, total: 200, shown: 50 });
    expect(note).toContain('150+ more');
    expect(note).toContain('showing first 50');
  });

  it('mentions when the limit was capped by the server', () => {
    const note = formatTruncationNote({ ...baseArgs, truncated: true, total: 5000, shown: 1000, limit: 1000, capped: true });
    expect(note).toContain('limit was capped to 1000 by the server');
  });

  it('omits the cap note when the user-supplied limit was within the ceiling', () => {
    const note = formatTruncationNote({ ...baseArgs, truncated: true, total: 200, shown: 50, capped: false });
    expect(note).not.toContain('capped');
  });
});
