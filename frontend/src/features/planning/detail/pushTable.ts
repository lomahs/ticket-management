import { stepLabel } from '../../../shared/format/labels';
import type { DetailPlanView, MatrixLine } from './api';

/** What the push checks on each line, in the order of the table's columns. */
export const CHECKS = [
  { k: 'task', label: 'Task' },
  { k: 'pic', label: 'PIC' },
  { k: 'not_yet', label: 'Not yet plan' },
  { k: 'order', label: 'Step order' },
] as const;

export type Check = (typeof CHECKS)[number]['k'];

/** The checks Force push goes past; the others are needed to create a sub-task at all. */
export const FORCEABLE: ReadonlySet<Check> = new Set<Check>(['not_yet', 'order']);

/**
 * A line the push sends, with each check: null when it does not apply to the line (a sub-task in Jira has no Task or slot to
 * pick here), [] when it passes, else what to change.
 */
export interface PushRow { ref: string; key: string | null; label: string; checks: Record<Check, string[] | null> }

/** The lines a push of `refs` sends (all when null): not removed, not done, and not in Jira yet or changed here. */
export const sentLines = (v: DetailPlanView, refs: string[] | null) => v.lines.filter((l) => !l.removed && !l.closed
  && (l.row_id != null || (l.issue_key != null && l.pending)) && (refs == null || refs.includes(l.ref)));

/** A problem's message without the line or row it names, which its table row already shows. */
function bare(msg: string, l: MatrixLine): string {
  if (msg.startsWith(`${l.summary}: `)) return msg.slice(l.summary.length + 2);
  const row = /^Row \d+ \(.*?\): /.exec(msg);
  return row ? msg.slice(row[0].length) : msg;
}

const label = (l: MatrixLine) => (l.step ? `${l.function} – ${stepLabel(l.step)}` : l.function);

/**
 * What stands before a push of `refs`, as a table: `general` the problems about no line (a draft anken without its epic), `rows`
 * every line sent, `failing` those with an X. A row's Task problem is on each of its lines, its PIC problem on the lines of the
 * slot it is about (on all of them when none has that slot as its PIC); the problems of a row with no line sent are `general`.
 */
export function pushTable(v: DetailPlanView, refs: string[] | null): { general: string[]; rows: PushRow[]; failing: PushRow[] } {
  const general = [...new Set(v.issues.filter((i) => i.ref == null && i.row_id == null).map((i) => i.message))];
  const lines = sentLines(v, refs);
  // a slot no line has as its PIC (a BAL with only a share of a step) stands on every line of its row; a row with no line sent
  // has its problems above the table
  const slotOf = (field: string) => field.slice(4);
  const homeless = (i: DetailPlanView['issues'][number]) => i.field.startsWith('pic_')
    && !lines.some((l) => l.row_id === i.row_id && l.issue_key == null && l.pic_slot === slotOf(i.field));
  const rowIssues = v.issues.filter((i) => i.ref == null && i.row_id != null);
  const sentRows = new Set(lines.map((l) => l.row_id));
  if (refs == null) general.push(...new Set(rowIssues.filter((i) => !sentRows.has(i.row_id)).map((i) => i.message)));
  const rows = lines.map((l): PushRow => {
    const inRow = l.issue_key == null && l.row_id != null;
    const of = (pick: (i: DetailPlanView['issues'][number]) => boolean) => [...new Set(v.issues.filter(pick).map((i) => bare(i.message, l)))];
    return {
      ref: l.ref,
      key: l.issue_key ?? null,
      label: label(l),
      checks: {
        task: inRow ? of((i) => i.row_id === l.row_id && i.ref == null && i.field === 'task') : null,
        pic: inRow ? of((i) => i.row_id === l.row_id && i.ref == null && (i.field === `pic_${l.pic_slot}` || homeless(i))) : null,
        not_yet: of((i) => i.ref === l.ref && i.field === 'not_yet'),
        order: of((i) => i.ref === l.ref && i.field === 'order'),
      },
    };
  });
  return { general, rows, failing: rows.filter((r) => CHECKS.some((c) => (r.checks[c.k]?.length ?? 0) > 0)) };
}
