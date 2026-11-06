import { describe, expect, it } from 'vitest';
import type { DetailPlanView } from './api';
import { jiraCsv, planCsv } from './csv';

type Step = DetailPlanView['steps'][number];
type Row = DetailPlanView['rows'][number];

const row = (over: Partial<Row>): Row => ({
  id: 1, epic_key: 'PAY-1100', position: 0, phase: 'ITA', subphase: 'Create test case', function_name: 'Login', steps: 5, effort_h: 8,
  ba_id: 'an', bal_id: 'minh', jm_id: 'tanaka', task_key: 'PAY-1102', pushed_at: null, ...over,
});
const step = (over: Partial<Step>): Step => ({
  ref: '1:create', row_id: 1, step: 'create', phase: 'ITA', summary: '[PAY] Login – Create', effort_h: 8, ba_h: 8, bal_h: 0,
  start: '2026-10-12', end: '2026-10-13', manual: false, edited: false, removed: false, assignee_id: 'an', reporter_id: 'minh', ...over,
});
const view = (rows: Row[], steps: Step[]) => ({ rows, steps } as unknown as DetailPlanView);

const v = view([row({}), row({ id: 2, function_name: 'Run', steps: null, bal_id: null, jm_id: null, task_key: 'PAY-1103' }),
  row({ id: 3, function_name: 'Done', pushed_at: '2026-10-07T09:00:00Z' })], [
  step({}),
  step({ ref: '1:balr', step: 'balr', summary: '[PAY] Login – BAL Review', effort_h: 1.5, ba_h: 0, bal_h: 1.5, assignee_id: 'an', reporter_id: null, manual: true }),
  step({ ref: '1:fixb', step: 'fixb', summary: '[PAY] Login – Fix comment BAL', removed: true }),
  step({ ref: '2', row_id: 2, step: null, summary: '[PAY] Run', effort_h: 16, ba_h: 16, start: null, end: null, reporter_id: null }),
  step({ ref: '3:create', row_id: 3, summary: '[PAY] Done – Create' }),
]);
const names: Record<string, string> = { an: 'An', minh: 'Minh', tanaka: 'Tanaka' };

describe('detail plan CSV', () => {
  it('lists the lines of the rows not in Jira yet, removed ones aside, with names and hours', () => {
    const c = planCsv(v, (id) => (id ? names[id] : ''));
    expect(c.header).toEqual(['Phase', 'Function', 'Step', 'Summary', 'Task', 'BA', 'BAL', 'JM', 'Assignee', 'Effort (h)', 'BA (h)', 'BAL (h)',
      'Plan start', 'Plan end', 'Set by hand']);
    expect(c.rows).toEqual([
      ['ITA', 'Login', 'Create', '[PAY] Login – Create', 'PAY-1102', 'An', 'Minh', 'Tanaka', 'An', 8, 8, 0, '2026-10-12', '2026-10-13', ''],
      ['ITA', 'Login', 'BAL Review', '[PAY] Login – BAL Review', 'PAY-1102', 'An', 'Minh', 'Tanaka', 'An', 1.5, 0, 1.5, '2026-10-12', '2026-10-13', 'Yes'],
      ['ITA', 'Run', 'Ticket', '[PAY] Run', 'PAY-1103', 'An', '', '', 'An', 16, 16, 0, null, null, ''],
    ]);
  });

  it('gives Jira the sub-tasks the push would create, with seconds and one Labels column per label', () => {
    const c = jiraCsv(v, (id) => (id ? `${id}@acme.vn` : ''), ['PAY', 'phase']);
    expect(c.header).toEqual(['Issue Type', 'Summary', 'Parent', 'Assignee', 'Reporter', 'Start date', 'End date', 'Due date', 'Original Estimate',
      'Labels', 'Labels']);
    expect(c.rows).toEqual([ // Run has no dates yet: created without them, as the push does
      ['Sub-task', '[PAY] Login – Create', 'PAY-1102', 'an@acme.vn', 'minh@acme.vn', '2026-10-12', '2026-10-13', '2026-10-13', 28800, 'PAY', 'phase'],
      ['Sub-task', '[PAY] Login – BAL Review', 'PAY-1102', 'an@acme.vn', '', '2026-10-12', '2026-10-13', '2026-10-13', 5400, 'PAY', 'phase'],
      ['Sub-task', '[PAY] Run', 'PAY-1103', 'an@acme.vn', '', null, null, null, 57600, 'PAY', 'phase'],
    ]);
  });
});
