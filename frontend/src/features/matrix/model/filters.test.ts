import { describe, expect, it } from 'vitest';
import type { Schemas } from '../../../shared/api/types';
import { applyFilter, fromSearch, pendingAtStep, stepRange, summaryCounts, toSearch, type MatrixFilter } from './filters';

type Row = Schemas['FunctionRow'];
const cell = (ps: string, pe: string, late = false) => ({ planned_start: ps, planned_end: pe, late }) as unknown as Schemas['TaskCard'];
const row = (id: string, o: Partial<Row> = {}): Row => ({
  id, name: `Fn ${id}`, owner_id: 'an', reviewer_id: 'minh', current: 1, state: 'ontime', progress: 'ontime', group: 'W', pct: 40,
  cells: [cell('2026-10-01', '2026-10-02'), cell('2026-10-05', '2026-10-05'), cell('2026-10-06', '2026-10-06'), null, null],
  logged_h: 1, estimate_h: 2, ...o,
});
const ROWS = [
  row('F01'), row('F02', { owner_id: 'binh', group: 'N', progress: 'late', current: 0, cells: [cell('2026-09-01', '2026-09-02', true), null, null, null, null] }),
  row('F03', { reviewer_id: 'zz', group: 'D', current: 5 }),
];
const none: MatrixFilter = { q: '', members: [], status: [], prog: [], ranges: {}, sort: null };

describe('matrix filters', () => {
  it('round-trips through URL search params', () => {
    const f: MatrixFilter = { q: 'pay', members: ['an', 'binh'], status: ['W'], prog: ['late'], ranges: { 2: { from: '2026-10-01' } }, sort: { i: 2, dir: -1 } };
    const s = toSearch(f);
    expect(s).toEqual({ q: 'pay', members: 'an,binh', status: 'W', prog: 'late', sort: '2:desc', r2: '2026-10-01..' });
    expect(fromSearch(s)).toEqual(f);
    expect(toSearch(none)).toEqual({});
  });

  it('filters by member (owner or reviewer), status group, progress and text', () => {
    expect(applyFilter(ROWS, { ...none, members: ['binh'] }).map((r) => r.id)).toEqual(['F02']);
    expect(applyFilter(ROWS, { ...none, members: ['minh'] }).map((r) => r.id)).toEqual(['F01', 'F02']);
    expect(applyFilter(ROWS, { ...none, status: ['D'] }).map((r) => r.id)).toEqual(['F03']);
    expect(applyFilter(ROWS, { ...none, prog: ['late'] }).map((r) => r.id)).toEqual(['F02']);
    expect(applyFilter(ROWS, { ...none, q: 'f03' }).map((r) => r.id)).toEqual(['F03']);
  });

  it('filters by a step date range and sorts by planned start', () => {
    expect(applyFilter(ROWS, { ...none, ranges: { 0: { from: '2026-09-15' } } }).map((r) => r.id)).toEqual(['F01', 'F03']);
    expect(applyFilter(ROWS, { ...none, sort: { i: 0, dir: 1 } }).map((r) => r.id)).toEqual(['F02', 'F01', 'F03']);
  });

  it('counts the status summary per member', () => {
    const c = summaryCounts(ROWS, ['an', 'binh'], ['minh']);
    expect(c.find((x) => x.memberId === 'an')!.counts).toEqual({ N: 0, W: 1, D: 1, late: 0 });
    expect(c.find((x) => x.memberId === 'minh')!.counts).toEqual({ N: 1, W: 1, D: 0, late: 1 });
    expect(c.find((x) => x.memberId === null)!.total).toBe(3);
  });

  it('computes step ranges and functions pending at each step', () => {
    expect(stepRange(ROWS, 0)).toEqual(['2026-09-01', '2026-10-02']);
    expect(stepRange(ROWS, 4)).toBeNull();
    const p = pendingAtStep(ROWS);
    expect(p.map((x) => x.count)).toEqual([1, 1, 0, 0, 0]);
    expect(p[0].late).toBe(1);
    expect(p.findIndex((x) => x.bottleneck)).toBe(0);
  });
});
