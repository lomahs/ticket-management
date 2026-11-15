import { seconds } from '../../../shared/format/csv';
import { STEPS } from '../../../shared/format/labels';
import type { DetailPlanView, MatrixLine } from './api';

type Cell = string | number | null | undefined;
export interface Csv { header: string[]; rows: Cell[][] }

const stepLabel = (l: MatrixLine) => STEPS.find((x) => x.k === l.step)?.label ?? 'Ticket';

/** The hours of a line on a day; null when nothing is planned on it. */
const hoursOn = (l: MatrixLine, day: string) => {
  const h = l.cells.filter((c) => c.day === day).reduce((a, c) => a + c.hours, 0);
  return h > 0 ? h : null;
};

/** The plan as the matrix shows it, to read or share: one row per line, removed ones aside, then a column per day of the plan. */
export function planCsv(v: DetailPlanView, name: (id?: string | null) => string): Csv {
  return {
    header: ['Phase', 'Function', 'Step', 'Summary', 'Key', 'PIC', 'Total (h)', 'Planned (h)', 'Not yet (h)', 'Start', 'End', ...v.window],
    rows: v.lines.filter((l) => !l.removed).map((l) => [l.phase, l.function, stepLabel(l), l.summary, l.issue_key ?? '', name(l.pic_id),
      l.total_h, l.planned_h, l.not_yet_h, l.start, l.end, ...v.window.map((d) => hoursOn(l, d))]),
  };
}

/**
 * The sub-tasks the push would create, for Jira › Import work items from CSV: the lines of the rows not in Jira yet, removed ones
 * aside, under their Task, with the push's assignee, reporter, dates (Due date = End), estimate in seconds and labels (one Labels
 * column each, as Jira's importer takes them). Lines not planned yet go without dates, as in the push.
 */
export function jiraCsv(v: DetailPlanView, email: (id?: string | null) => string, labels: string[]): Csv {
  const task = new Map(v.rows.map((r) => [r.id, r.task_key]));
  return {
    header: ['Issue Type', 'Summary', 'Parent', 'Assignee', 'Reporter', 'Start date', 'End date', 'Due date', 'Original Estimate', ...labels.map(() => 'Labels')],
    rows: v.lines.filter((l) => l.row_id != null && !l.removed).map((l) => ['Sub-task', l.summary, task.get(l.row_id ?? 0), email(l.pic_id),
      email(l.reporter_id), l.start, l.end, l.end, seconds(l.total_h), ...labels]),
  };
}
