import { describe, expect, it } from 'vitest';
import { dm, f2, hrs, longDate, parseYmd, relTime, ym, ymd, ymdSpan } from './dates';
import { signedH } from './labels';

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

describe('Japanese date order', () => {
  it('shows month/day, and year/month/day with the year', () => {
    expect(dm('2026-10-07')).toBe('10/07');
    expect(ymd('2026-10-07')).toBe('2026/10/07');
    expect(ym('2026-10')).toBe('2026/10');
    expect(longDate('2026-10-07')).toBe('Wednesday, 2026/10/07');
    expect(ymdSpan('2026-10-05', '2026-10-11')).toBe('2026/10/05 – 10/11');
    expect(ymdSpan('2026-12-28', '2027-01-03')).toBe('2026/12/28 – 2027/01/03'); // the year once per year
    expect(dm(null)).toBe('');
    expect(ymd('')).toBe('');
  });

  it('reads a typed date as yyyy/MM/dd, yyyy-MM-dd or yyyyMMdd', () => {
    expect(parseYmd('2026/10/07')).toBe('2026-10-07');
    expect(parseYmd(' 2026-10-07 ')).toBe('2026-10-07');
    expect(parseYmd('20261007')).toBe('2026-10-07');
    expect(parseYmd('2026/1/5')).toBeNull(); // complete only with two-digit month and day …
    expect(parseYmd('2026/1/5', true)).toBe('2026-01-05'); // … unless the field is left
    expect(parseYmd('2026/02/30')).toBeNull();
    expect(parseYmd('2026/13/01')).toBeNull();
    expect(parseYmd('10/07/2026')).toBeNull();
    expect(parseYmd('')).toBeNull();
  });
});

describe('hours', () => {
  it('rounds half-up to two decimals and always shows them', () => {
    expect(f2(8)).toBe('8.00');
    expect(f2(7.5)).toBe('7.50');
    expect(f2(1 / 3)).toBe('0.33');
    expect(f2(1.005)).toBe('1.01'); // not 1.00 from floating point
    expect(f2(0.1 + 0.2)).toBe('0.30');
    expect(f2(-0.001)).toBe('0.00'); // no "-0.00"
    expect(hrs(2.675)).toBe('2.68h');
    expect(hrs(null)).toBe('–');
    expect(signedH(1.5)).toBe('+1.50h');
    expect(signedH(-14)).toBe('−14.00h');
    expect(signedH(-0.001)).toBe('+0.00h');
  });
});
