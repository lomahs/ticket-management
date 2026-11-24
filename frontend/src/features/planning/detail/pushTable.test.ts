import { describe, expect, it } from 'vitest';
import type { DetailPlanView } from './api';
import { pushTable } from './pushTable';

type Line = DetailPlanView['lines'][number];
type Issue = DetailPlanView['issues'][number];

const line = (over: Partial<Line>): Line => ({
  ref: '1:create', row_id: 1, issue_key: null, phase: 'ITA', function: 'Login', step: 'create', summary: '[PAY] Login – Create', pic_id: 'an',
  reporter_id: 'minh', total_h: 8, spent_h: 0, planned_h: 8, not_yet_h: 0, start: '2026-10-12', end: '2026-10-13', cells: [], logged: [],
  closed: false, pending: false, out_of_order: false, removed: false, edited: false, plan_estimate_h: 8, plan_h: 8, plan_not_yet_h: 0,
  subphase: 'Create test case', pic_slot: 'ba', linked_row_id: null, actual_remaining_h: null, ...over,
});
/** A problem: `message` names its row or line ("{subject}: {detail}"), `detail` does not. */
const issue = (over: Partial<Issue> & { message: string }): Issue => ({ row_id: null, ref: null, field: 'task',
  detail: over.message.slice(over.message.lastIndexOf(': ') + 2), ...over });
const view = (lines: Line[], issues: Issue[]) => ({ lines, issues } as unknown as DetailPlanView);

const create = line({});
const balr = line({ ref: '1:balr', step: 'balr', summary: '[PAY] Login – BAL Review', pic_slot: 'bal' });
const jira = line({ ref: 'PAY-9001', row_id: null, issue_key: 'PAY-9001', step: null, function: 'Export', summary: '[PAY] Export', pending: true });
const lines = [create, balr, jira,
  line({ ref: 'PAY-9002', row_id: null, issue_key: 'PAY-9002', summary: 'not pending' }),
  line({ ref: '1:jmr', step: 'jmr', removed: true }),
  line({ ref: 'PAY-9003', row_id: null, issue_key: 'PAY-9003', pending: true, closed: true })];

describe('pushTable', () => {
  it('puts a row problem on its lines, a slot problem on its slot, a line problem on its line', () => {
    const t = pushTable(view(lines, [
      issue({ row_id: 1, field: 'task', message: 'Row 1 (Login): choose a Task' }),
      issue({ row_id: 1, field: 'pic_bal', message: 'Row 1 (Login): pick a BAL' }),
      issue({ ref: '1:create', field: 'not_yet', message: '[PAY] Login – Create: 2h not planned yet' }),
      issue({ ref: 'PAY-9001', field: 'order', message: '[PAY] Export: starts before the step before it ends' }),
    ]), null);
    expect(t.rows.map((r) => r.ref)).toEqual(['1:create', '1:balr', 'PAY-9001']);
    expect(t.rows[0]).toMatchObject({ label: 'Login – Create', checks: { task: ['choose a Task'], pic: [], not_yet: ['2h not planned yet'], order: [] } });
    expect(t.rows[1].checks).toEqual({ task: ['choose a Task'], pic: ['pick a BAL'], not_yet: [], order: [] });
    expect(t.rows[2]).toMatchObject({ key: 'PAY-9001', label: 'Export', checks: { task: null, pic: null, not_yet: [], order: ['starts before the step before it ends'] } });
    expect(t.failing).toHaveLength(3);
    expect(t.general).toEqual([]);
  });

  it('puts a slot no line has as its PIC on every line of its row, and a row with no line sent above the table', () => {
    const t = pushTable(view([create, line({ ref: '2', row_id: 2, step: null, function: 'Run', removed: true })], [
      issue({ row_id: 1, field: 'pic_bal', message: 'Row 1 (Login): pick a BAL' }), // the BAL has a share of Create only
      issue({ row_id: 2, field: 'task', message: 'Row 2 (Run): choose a Task' }),
    ]), null);
    expect(t.rows[0].checks.pic).toEqual(['pick a BAL']);
    expect(t.general).toEqual(['Row 2 (Run): choose a Task']);
  });

  it('shows each problem without naming its row, whatever the function is called', () => {
    const odd = line({ function: 'Pay (card): refund', summary: '[PAY] Pay (card): refund – Create' });
    const t = pushTable(view([odd], [issue({ row_id: 1, field: 'task', message: 'Row 1 (Pay (card): refund): choose a Task', detail: 'choose a Task' })]), null);
    expect(t.rows[0].checks.task).toEqual(['choose a Task']);
  });

  it('keeps to the picked lines and lists the problems about no line apart', () => {
    const t = pushTable(view(lines, [
      issue({ field: 'epic', message: 'Link the anken\'s epic in Jira first' }),
      issue({ ref: '1:create', field: 'not_yet', message: '[PAY] Login – Create: 2h not planned yet' }),
    ]), ['1:balr', 'PAY-9001']);
    expect(t.rows.map((r) => r.ref)).toEqual(['1:balr', 'PAY-9001']);
    expect(t.failing).toEqual([]);
    expect(t.general).toEqual(['Link the anken\'s epic in Jira first']);
  });
});
