import { describe, expect, it } from 'vitest';
import { clusterByFunction, stripes } from './clusters';

const it_ = (key: string, start: string, end: string, group: string | null, order = 0) => ({ key, start, end, group, order });

describe('function clusters', () => {
  it('merges steps of one function that share a day, in step order', () => {
    const out = clusterByFunction([
      it_('FIX', '2026-10-07', '2026-10-08', 'F03', 2),
      it_('CREATE', '2026-10-06', '2026-10-07', 'F03', 0),
      it_('LATER', '2026-10-12', '2026-10-12', 'F03', 4), // no shared day: its own block
      it_('OTHER', '2026-10-07', '2026-10-07', 'F04', 0), // another function
      it_('TASK', '2026-10-07', '2026-10-07', null),
    ]);
    const by = Object.fromEntries(out.map((c) => [c.key, c]));
    expect(Object.keys(by).sort()).toEqual(['CREATE', 'LATER', 'OTHER', 'TASK']);
    expect(by.CREATE).toMatchObject({ start: '2026-10-06', end: '2026-10-08' });
    expect(by.CREATE.items.map((x) => x.key)).toEqual(['CREATE', 'FIX']);
  });

  it('chains overlaps and keeps tickets without a function apart', () => {
    const out = clusterByFunction([
      it_('A', '2026-10-05', '2026-10-06', 'F', 0), it_('B', '2026-10-06', '2026-10-08', 'F', 1), it_('C', '2026-10-08', '2026-10-09', 'F', 2),
      it_('X', '2026-10-05', '2026-10-05', null), it_('Y', '2026-10-05', '2026-10-05', null),
    ]);
    expect(out.map((c) => c.items.length).sort()).toEqual([1, 1, 3]);
  });

  it('splits the colour into equal stripes', () => {
    expect(stripes(['red'])).toBe('red');
    expect(stripes(['red', 'blue'])).toBe('linear-gradient(to right,red 0.00% 50.00%,blue 50.00% 100.00%)');
  });
});
