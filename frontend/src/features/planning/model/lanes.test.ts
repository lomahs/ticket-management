import { describe, expect, it } from 'vitest';
import { layoutLanes, startForDrop } from './lanes';

const DAYS = ['2026-10-05', '2026-10-06', '2026-10-07', '2026-10-08', '2026-10-09', '2026-10-12'];

describe('gantt lanes', () => {
  it('places items on visible columns and stacks overlaps in lanes', () => {
    const r = layoutLanes([
      { key: 'A', start: '2026-10-05', end: '2026-10-06' },
      { key: 'B', start: '2026-10-06', end: '2026-10-07' },
      { key: 'C', start: '2026-10-08', end: '2026-10-08' },
      { key: 'D', start: '2026-10-03', end: '2026-10-11' }, // starts before the window, ends on a hidden weekend day
    ], DAYS);
    const by = Object.fromEntries(r.blocks.map((b) => [b.key, b]));
    expect(by.D).toMatchObject({ col: 0, span: 5, lane: 0 });
    expect(by.A).toMatchObject({ col: 0, span: 2, lane: 1 });
    expect(by.B).toMatchObject({ col: 1, span: 2, lane: 2 });
    expect(by.C).toMatchObject({ col: 3, span: 1, lane: 1 });
    expect(r.lanes).toBe(3);
  });

  it('skips items entirely after the window', () => {
    expect(layoutLanes([{ key: 'X', start: '2026-10-20', end: '2026-10-21' }], DAYS).blocks).toEqual([]);
  });

  it('maps a drop column back to a start date, keeping the grab offset', () => {
    // block starting on a hidden Sunday (offset −1 day from its first visible column) dropped on column 5 (12/10)
    expect(startForDrop(DAYS, 5, '2026-10-04', 0)).toBe('2026-10-11');
    expect(startForDrop(DAYS, 2, '2026-10-05', 0)).toBe('2026-10-07');
  });
});
