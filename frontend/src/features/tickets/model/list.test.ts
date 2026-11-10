import { describe, expect, it } from 'vitest';
import type { Schemas } from '../../../shared/api/types';
import { clickSort, itemsOf, parseSort, sortItems, sortString } from './list';

const row = (key: string, extra: Partial<Schemas['BoardRow']> = {}): Schemas['BoardRow'] => ({
  key, summary: key, status: 'W', assignee_id: null, reporter_id: null, planned_start: null, planned_end: null, estimate_h: 0, step: null, ...extra,
});
const board: Schemas['TicketBoard'] = {
  epic: 'PAY-1100', title: 'Payment', project: 'PAY', site: null, templates: [],
  tasks: [
    { task: row('PAY-1101'), phase: 'Prepare', workflow: false, subtasks: [
      row('PAY-1104', { assignee_id: 'binh', planned_start: '2026-10-07' }),
      row('PAY-999', { assignee_id: 'an', planned_start: '2026-10-09' }),
      row('PAY-1000', { assignee_id: 'an', planned_start: '2026-10-05' }),
      row('PAY-1105'),
    ] },
  ],
};
const name = (id: string) => ({ an: 'An', binh: 'Bình' }[id] ?? id);
const keys = (by: string) => sortItems(itemsOf(board, []).filter((it) => !it.task), parseSort(by), name).map((it) => it.id);

describe('the ticket list', () => {
  it('lists each Task with its sub-tasks, then new Tasks with theirs', () => {
    const news = [
      { ref: 't', subtask: false, summary: 'New', assignee_id: 'an', reporter_id: '', planned_start: '', planned_end: '', estimate_h: '' },
      { ref: 's', subtask: true, parent_ref: 't', summary: 'Sub', assignee_id: 'an', reporter_id: '', planned_start: '', planned_end: '', estimate_h: '' },
      { ref: 'u', subtask: true, parent_key: 'PAY-1101', summary: 'Sub', assignee_id: 'an', reporter_id: '', planned_start: '', planned_end: '', estimate_h: '' },
    ];
    expect(itemsOf(board, news).map((it) => `${it.id}<${it.parent}`)).toEqual([
      'PAY-1101<PAY-1100', 'PAY-1104<PAY-1101', 'PAY-999<PAY-1101', 'PAY-1000<PAY-1101', 'PAY-1105<PAY-1101', 'u<PAY-1101', 't<PAY-1100', 's<<new:t>',
    ]);
  });

  it('sorts keys by their number and empty values last both ways', () => {
    expect(keys('key')).toEqual(['PAY-999', 'PAY-1000', 'PAY-1104', 'PAY-1105']);
    expect(keys('-key')).toEqual(['PAY-1105', 'PAY-1104', 'PAY-1000', 'PAY-999']);
    expect(keys('start')).toEqual(['PAY-1000', 'PAY-1104', 'PAY-999', 'PAY-1105']);
    expect(keys('-start')).toEqual(['PAY-999', 'PAY-1104', 'PAY-1000', 'PAY-1105']);
  });

  it('sorts by several columns in order, people by name', () => {
    expect(keys('assignee,-start')).toEqual(['PAY-999', 'PAY-1000', 'PAY-1104', 'PAY-1105']);
    expect(keys('-assignee,start')).toEqual(['PAY-1104', 'PAY-1000', 'PAY-999', 'PAY-1105']);
  });

  it('sorts by one column on a click, adds a column on Shift+click, and turns a column around on a second click', () => {
    let by = clickSort([], 'assignee', false);
    expect(sortString(by)).toBe('assignee');
    by = clickSort(by, 'start', true);
    expect(sortString(by)).toBe('assignee,start');
    expect(sortString(clickSort(by, 'start', true))).toBe('assignee,-start');
    expect(sortString(clickSort(by, 'key', false))).toBe('key');
    expect(sortString(clickSort([{ k: 'key', desc: false }], 'key', false))).toBe('-key');
    expect(parseSort('nope,-end,end')).toEqual([{ k: 'end', desc: true }]);
  });
});
