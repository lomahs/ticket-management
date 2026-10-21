import { describe, expect, it } from 'vitest';
import type { TaskCard } from '../api/types';
import { dueLabel, taskSub, taskTitle } from './task';

const card = (over: Partial<TaskCard> = {}): TaskCard => ({
  key: 'PAY-1213', summary: '[Implement_Coding][F03] Domestic card payment – Fix comment BAL', project: 'PAY', epic_key: 'PAY-1100',
  phase: 'Implement_Coding', function_id: 'F03', function_name: 'Domestic card payment', step: 'fixb', status: 'W', jira_status: 'In Progress',
  assignee_id: 'binh', worker_id: 'binh', planned_start: '2026-10-01', planned_end: '2026-10-06', estimate_h: 4, logged_h: 7.5, remaining_h: 0,
  actual_start: '2026-10-01', actual_end: '2026-10-07', is_current: true, late: true, late_days: 1, soon: false, over: true, tone: 'delay',
  held_days: 2, rounds: 3,
  schedule: {
    start: 'on_time_start', start_days: 0, finish: 'overdue', finish_days: 1, effort: 'on_plan', plan_to_date_h: 4, actual_to_date_h: 4, diff_h: 0,
  },
  ...over,
});

describe('task display', () => {
  it('titles function steps by id and name, other tasks by summary', () => {
    expect(taskTitle(card())).toBe('F03 Domestic card payment');
    expect(taskTitle(card({ function_id: null, function_name: null, summary: 'Prepare Q&A list' }))).toBe('Prepare Q&A list');
  });
  it('describes step, rounds and held days', () => {
    expect(taskSub(card())).toBe('Fix comment BAL · round 3 · held 2 days');
    expect(taskSub(card({ function_id: null, step: null }))).toBe('Implement_Coding');
  });
  it('labels the deadline', () => {
    expect(dueLabel(card())).toBe('06/10 · late 1d');
    expect(dueLabel(card({ late: false }))).toBe('06/10');
    expect(dueLabel(card({ planned_end: null, late: false }))).toBe('–');
  });
});
