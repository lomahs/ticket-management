import { describe, expect, it } from 'vitest';
import type { DetailPlanView } from './api';
import { jiraCsv, planCsv } from './csv';

type Line = DetailPlanView['lines'][number];
type Row = DetailPlanView['rows'][number];

const row = (over: Partial<Row>): Row => ({
  id: 1, epic_key: 'PAY-1100', position: 0, phase: 'ITA', subphase: 'Create test case', function_name: 'Login', steps: 5, effort_h: 8,
  ba_id: 'an', bal_id: 'minh', jm_id: 'tanaka', task_key: 'PAY-1102', pushed_at: null, ...over,
});
const line = (over: Partial<Line>): Line => ({
  ref: '1:create', row_id: 1, issue_key: null, phase: 'ITA', function: 'Login', step: 'create', summary: '[PAY] Login – Create', pic_id: 'an',
  reporter_id: 'minh', total_h: 8, spent_h: 0, planned_h: 8, not_yet_h: 0, start: '2026-10-12', end: '2026-10-13',
  cells: [{ day: '2026-10-12', hours: 5, auto: true }, { day: '2026-10-13', hours: 3, auto: false }], logged: [], closed: false, pending: false,
  out_of_order: false, removed: false, edited: false, plan_estimate_h: 8, plan_h: 8, plan_not_yet_h: 0, subphase: 'Create test case', pic_slot: 'ba', ...over,
});
const view = (rows: Row[], lines: Line[]) => ({ rows, lines, window: ['2026-10-12', '2026-10-13', '2026-10-14'] } as unknown as DetailPlanView);

const v = view([row({}), row({ id: 2, function_name: 'Run', steps: null, bal_id: null, jm_id: null, task_key: 'PAY-1103' })], [
  line({}),
  line({ ref: '1:balr', step: 'balr', summary: '[PAY] Login – BAL Review', total_h: 1.5, planned_h: 0, not_yet_h: 1.5, start: null, end: null, cells: [],
    reporter_id: null, edited: true }),
  line({ ref: '1:fixb', step: 'fixb', summary: '[PAY] Login – Fix comment BAL', removed: true }),
  line({ ref: '2', row_id: 2, function: 'Run', step: null, summary: '[PAY] Run', total_h: 16, planned_h: 0, not_yet_h: 16, start: null, end: null,
    cells: [], reporter_id: null }),
  line({ ref: 'PAY-1201', row_id: null, issue_key: 'PAY-1201', function: 'Refund', summary: '[PAY] Refund – Create', total_h: 3, spent_h: 1,
    planned_h: 3, not_yet_h: 0, start: '2026-10-13', end: '2026-10-14', cells: [{ day: '2026-10-14', hours: 2, auto: false }], pending: true }),
]);
const names: Record<string, string> = { an: 'An', minh: 'Minh', tanaka: 'Tanaka' };

describe('detail plan CSV', () => {
  it('lists every line of the matrix, removed ones aside, with a column per day of the plan', () => {
    const c = planCsv(v, (id) => (id ? names[id] : ''));
    expect(c.header).toEqual(['Phase', 'Function', 'Step', 'Summary', 'Key', 'PIC', 'Total (h)', 'Planned (h)', 'Not yet (h)', 'Start', 'End',
      '2026-10-12', '2026-10-13', '2026-10-14']);
    expect(c.rows).toEqual([
      ['ITA', 'Login', 'Create', '[PAY] Login – Create', '', 'An', 8, 8, 0, '2026-10-12', '2026-10-13', 5, 3, null],
      ['ITA', 'Login', 'BAL Review', '[PAY] Login – BAL Review', '', 'An', 1.5, 0, 1.5, null, null, null, null, null],
      ['ITA', 'Run', 'Ticket', '[PAY] Run', '', 'An', 16, 0, 16, null, null, null, null, null],
      ['ITA', 'Refund', 'Create', '[PAY] Refund – Create', 'PAY-1201', 'An', 3, 3, 0, '2026-10-13', '2026-10-14', null, null, 2],
    ]);
  });

  it('gives Jira the sub-tasks the push would create, with seconds and one Labels column per label', () => {
    const c = jiraCsv(v, (id) => (id ? `${id}@acme.vn` : ''), ['PAY', 'phase']);
    expect(c.header).toEqual(['Issue Type', 'Summary', 'Parent', 'Assignee', 'Reporter', 'Start date', 'End date', 'Due date', 'Original Estimate',
      'Labels', 'Labels']);
    expect(c.rows).toEqual([ // not planned yet: created without dates, as the push does; the sub-task in Jira is not created again
      ['Sub-task', '[PAY] Login – Create', 'PAY-1102', 'an@acme.vn', 'minh@acme.vn', '2026-10-12', '2026-10-13', '2026-10-13', 28800, 'PAY', 'phase'],
      ['Sub-task', '[PAY] Login – BAL Review', 'PAY-1102', 'an@acme.vn', '', null, null, null, 5400, 'PAY', 'phase'],
      ['Sub-task', '[PAY] Run', 'PAY-1103', 'an@acme.vn', '', null, null, null, 57600, 'PAY', 'phase'],
    ]);
  });
});
