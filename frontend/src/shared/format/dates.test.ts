import { describe, expect, it } from 'vitest';
import { relTime } from './dates';

describe('relTime', () => {
  const now = Date.parse('2026-11-06T12:00:00Z');
  it('counts minutes, then hours, then days', () => {
    expect(relTime(null, now)).toBe('never');
    expect(relTime('2026-11-06T11:59:40Z', now)).toBe('just now');
    expect(relTime('2026-11-06T11:15:00Z', now)).toBe('45 minutes ago');
    expect(relTime('2026-11-06T07:00:00Z', now)).toBe('5 hours ago');
    expect(relTime('2026-11-05T11:00:00Z', now)).toBe('1 day ago');
    expect(relTime('2026-10-07T12:00:00Z', now)).toBe('30 days ago'); // not "720 hours ago"
  });
});
