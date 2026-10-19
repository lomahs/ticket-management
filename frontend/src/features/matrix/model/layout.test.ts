import { describe, expect, it } from 'vitest';
import type { Schemas } from '../../../shared/api/types';
import { applyLayout } from './layout';

const task = (key: string, group: string) => ({ key, group }) as Schemas['ParentOption'];
const PARENTS = [task('A1', 'ITA'), task('A2', 'ITA'), task('B1', 'ST'), task('C1', 'UAT'), task('C2', 'UAT')];
const keys = (l: Parameters<typeof applyLayout>[1]) => applyLayout(PARENTS, l).map((p) => p.key);

describe('applyLayout', () => {
  it('keeps the default order without a layout', () => {
    expect(keys(null)).toEqual(['A1', 'A2', 'B1', 'C1', 'C2']);
  });

  it('orders phases, then Tasks within each phase', () => {
    expect(keys({ phases: ['UAT', 'ITA', 'ST'], tasks: ['C2', 'C1', 'A2', 'A1'], shown: [] })).toEqual(['C2', 'C1', 'A2', 'A1', 'B1']);
  });

  it('puts phases and Tasks the layout does not know after the known ones, in their default order', () => {
    expect(keys({ phases: ['UAT'], tasks: ['A2'], shown: [] })).toEqual(['C1', 'C2', 'A2', 'A1', 'B1']);
  });
});
