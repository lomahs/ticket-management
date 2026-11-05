import { seconds } from '../../../shared/format/csv';
import { STEPS } from '../../../shared/format/labels';
import type { DetailPlanView } from './api';

type Step = DetailPlanView['steps'][number];
type Cell = string | number | null | undefined;
export interface Csv { header: string[]; rows: Cell[][] }

/** The sub-tasks of the rows not in Jira yet, the removed ones aside: the lines of the list. */
function lines(v: DetailPlanView) {
  const rows = new Map(v.rows.filter((r) => !r.pushed_at).map((r) => [r.id, r]));
  return v.steps.flatMap((s) => {
    const r = rows.get(s.row_id);
    return r && !s.removed ? [{ s, r }] : [];
  });
}

const stepLabel = (s: Step) => STEPS.find((x) => x.k === s.step)?.label ?? 'Ticket';

/** The plan as the list shows it, to read or share: names, hours and ISO dates. */
export function planCsv(v: DetailPlanView, name: (id?: string | null) => string): Csv {
  return {
    header: ['Phase', 'Function', 'Step', 'Summary', 'Task', 'BA', 'BAL', 'JM', 'Assignee', 'Effort (h)', 'BA (h)', 'BAL (h)', 'Plan start', 'Plan end', 'Set by hand'],
    rows: lines(v).map(({ s, r }) => [s.phase, r.function_name, stepLabel(s), s.summary, r.task_key, name(r.ba_id), r.steps ? name(r.bal_id) : '',
      r.steps ? name(r.jm_id) : '', name(s.assignee_id), s.effort_h, s.ba_h, s.bal_h, s.start, s.end, s.manual ? 'Yes' : '']),
  };
}

/**
 * The sub-tasks the push would create, for Jira › Import work items from CSV: under their Task, with the push's assignee,
 * reporter, dates (Due date = Plan end), estimate in seconds and labels (one Labels column each, as Jira's importer takes them).
 * Lines without dates (the plan cannot be computed) are left out.
 */
export function jiraCsv(v: DetailPlanView, email: (id?: string | null) => string, labels: string[]): Csv {
  return {
    header: ['Issue Type', 'Summary', 'Parent', 'Assignee', 'Reporter', 'Start date', 'End date', 'Due date', 'Original Estimate', ...labels.map(() => 'Labels')],
    rows: lines(v).filter(({ s }) => s.start && s.end).map(({ s, r }) => ['Sub-task', s.summary, r.task_key, email(s.assignee_id), email(s.reporter_id),
      s.start, s.end, s.end, seconds(s.effort_h), ...labels]),
  };
}
