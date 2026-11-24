import { describe, expect, it } from 'vitest';
import type { DetailPlanView, MatrixLine } from '../features/planning/detail/api';
import { hoursByDay, lineMarks, withTyped } from '../features/planning/detail/matrixModel';

type Cell = MatrixLine['cells'][number];

/** A line of the matrix, as the plan sends it: `est` hours to plan, `cells` planned. */
const line = (ref: string, est: number, cells: Cell[], over: Partial<MatrixLine> = {}): MatrixLine => {
  const planned = cells.reduce((a, c) => a + c.hours, 0);
  return {
    ref, row_id: null, linked_row_id: null, issue_key: null, phase: 'ITA', subphase: 'Create test case', function: 'Login', step: 'create',
    summary: ref, pic_id: null, pic_slot: null, reporter_id: null, total_h: est, spent_h: 0, planned_h: planned, not_yet_h: est - planned,
    plan_estimate_h: est, plan_h: planned, plan_not_yet_h: est - planned, actual_remaining_h: null, start: null, end: null, cells, logged: [],
    closed: false, pending: false, out_of_order: false, removed: false, edited: false, ...over,
  };
};
const view = (lines: MatrixLine[], over: Partial<DetailPlanView> = {}) => ({ lines, rows: [], issues: [], over_day: [], ...over }) as unknown as DetailPlanView;

describe('matrix model', () => {
  it('sums the hours of a day', () => {
    expect(hoursByDay([{ day: 'd1', hours: 2, auto: true }, { day: 'd1', hours: 1.5, auto: false }, { day: 'd2', hours: 1, auto: true }]))
      .toEqual(new Map([['d1', 3.5], ['d2', 1]]));
  });

  it('lays typed cells over the plan and keeps what did not change', () => {
    const v = view([line('a', 8, [{ day: 'd1', hours: 2, auto: true }]), line('b', 4, [])]);
    const t = withTyped(v, [{ ref: 'a', day: 'd1', hours: 5 }, { ref: 'a', day: 'd2', hours: 1 }]);
    expect(t.lines[0].cells).toEqual([{ day: 'd1', hours: 5, auto: false }, { day: 'd2', hours: 1, auto: false }]);
    expect([t.lines[0].plan_h, t.lines[0].plan_not_yet_h]).toEqual([6, 2]);
    expect(t.lines[1]).toBe(v.lines[1]);
    expect(v.lines[0].cells).toEqual([{ day: 'd1', hours: 2, auto: true }]); // the plan itself is left as it is
  });

  it('clears a day typed 0 and lets the last entry win', () => {
    const v = view([line('a', 8, [{ day: 'd1', hours: 2, auto: true }])]);
    expect(withTyped(v, [{ ref: 'a', day: 'd1', hours: 3 }, { ref: 'a', day: 'd1', hours: 0 }]).lines[0].cells).toEqual([]);
  });

  it('returns the plan itself when nothing is typed for its lines', () => {
    const v = view([line('a', 8, [])]);
    expect(withTyped(v, [])).toBe(v);
    expect(withTyped(v, [{ ref: 'gone', day: 'd1', hours: 2 }])).toBe(v);
  });

  it('puts each problem and day warning on its line', () => {
    const row = { id: 1, ba_id: 'an', bal_id: 'minh', jm_id: null, pushed_at: null };
    const create = line('1:create', 8, [], { row_id: 1, pic_slot: 'ba' });
    const review = line('1:balr', 2, [], { row_id: 1, pic_slot: 'bal' });
    const jira = line('PAY-9', 4, [], { issue_key: 'PAY-9', pic_id: 'binh', pic_slot: null });
    const removed = line('1:fixb', 1, [], { row_id: 1, pic_slot: 'ba', removed: true });
    const v = view([create, review, jira, removed], {
      rows: [row] as unknown as DetailPlanView['rows'],
      issues: [
        { row_id: null, ref: '1:create', field: 'not_yet', message: '8h not planned yet', detail: '8h not planned yet' },
        { row_id: null, ref: 'PAY-9', field: 'order', message: 'out of order', detail: 'out of order' },
        { row_id: null, ref: 'PAY-9', field: 'not_yet', message: '1h not planned yet', detail: '1h not planned yet' },
        { row_id: 1, ref: null, field: 'pic_bal', message: 'pick a BAL', detail: 'pick a BAL' },
        { row_id: 1, ref: null, field: 'task', message: 'choose a Task', detail: 'choose a Task' },
      ],
      over_day: [{ member_id: 'an', date: 'd1', planned_h: 9, allocation_h: 8 }, { member_id: 'binh', date: 'd2', planned_h: 9, allocation_h: 8 }],
    });
    const m = lineMarks(v);
    expect(m['1:create']).toEqual({ pic: [], notYet: ['8h not planned yet'], notYetJira: [], order: [], overDay: [v.over_day[0]] });
    expect(m['1:balr']).toEqual({ pic: ['pick a BAL'], notYet: [], notYetJira: [], order: [], overDay: [] });
    // a sub-task in Jira not changed here: the push does not send it, so its Not yet plan is a warning
    expect(m['PAY-9']).toEqual({ pic: [], notYet: [], order: ['out of order'], overDay: [v.over_day[1]],
      notYetJira: ['1h not planned yet · Jira\'s plan: it does not stop the push, changing it here does'] });
    expect(m['1:fixb']).toEqual({ pic: [], notYet: [], notYetJira: [], order: [], overDay: [] }); // a removed step: nothing to fix on it
  });
});
