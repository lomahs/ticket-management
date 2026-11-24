import { stepLabel } from '../../../shared/format/labels';
import type { DetailPlanView, MatrixLine } from './api';

/** The name of the review of a push, its dialog's label. */
export const PUSH_REVIEW = 'Push review';

/** Asks the plan matrix to show a line: its levels unfolded and the filters cleared when they hide it, then scrolled to. */
export const GO_TO_LINE = 'detail-plan:go';
export const goToLine = (ref: string) => window.dispatchEvent(new CustomEvent<string>(GO_TO_LINE, { detail: ref }));

type Issue = DetailPlanView['issues'][number];

/**
 * The PIC problems of a row on one of its steps: those of the step's slot, and of a slot none of the row's steps has (a BAL with
 * only a share of a step), which stand on every step. `slots`: the slots each row's steps have ({@link rowSlots}).
 */
export function picIssues(issues: Issue[], l: MatrixLine, slots: Map<number, Set<string>>): Issue[] {
  if (l.issue_key != null || l.row_id == null) return [];
  const has = slots.get(l.row_id);
  return issues.filter((i) => i.row_id === l.row_id && i.ref == null && i.field.startsWith('pic_')
    && (i.field === `pic_${l.pic_slot}` || !has?.has(i.field)));
}

/** The PIC slots ("pic_ba"…) the steps of each row have, of `lines`. */
export function rowSlots(lines: MatrixLine[]): Map<number, Set<string>> {
  const out = new Map<number, Set<string>>();
  for (const l of lines) if (l.issue_key == null && l.row_id != null && l.pic_slot && !l.removed) out.set(l.row_id, (out.get(l.row_id) ?? new Set()).add(`pic_${l.pic_slot}`));
  return out;
}

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

/** Whether a push sends the line: not removed, not done, and not in Jira yet or changed here. */
export const sends = (l: MatrixLine) => !l.removed && !l.closed && (l.row_id != null || (l.issue_key != null && l.pending));

/** The lines a push of `refs` sends (all when null). */
export const sentLines = (v: DetailPlanView, refs: string[] | null) => v.lines.filter((l) => sends(l) && (refs == null || refs.includes(l.ref)));

const label = (l: MatrixLine) => (l.step ? `${l.function} – ${stepLabel(l.step)}` : l.function);

/**
 * What stands before a push of `refs`, as a table: `general` the problems about no line (a draft anken without its epic), `rows`
 * every line sent, `failing` those with an X. A row's Task problem is on each of its lines, its PIC problem on the lines of the
 * slot it is about (on all of them when none has that slot as its PIC); the problems of a row with no line sent are `general`.
 */
export function pushTable(v: DetailPlanView, refs: string[] | null): { general: string[]; rows: PushRow[]; failing: PushRow[] } {
  const general = [...new Set(v.issues.filter((i) => i.ref == null && i.row_id == null).map((i) => i.message))];
  const lines = sentLines(v, refs);
  const byRow = new Map<number, Issue[]>();
  const byRef = new Map<string, Issue[]>();
  const into = <K>(m: Map<K, Issue[]>, k: K, i: Issue) => { const is = m.get(k); if (is) is.push(i); else m.set(k, [i]); };
  for (const i of v.issues) {
    if (i.ref != null) into(byRef, i.ref, i);
    else if (i.row_id != null) into(byRow, i.row_id, i);
  }
  const slots = rowSlots(v.lines); // of every step, as the matrix marks them, also when only some are picked
  // a row with no line sent has its problems above the table
  const sentRows = new Set(lines.map((l) => l.row_id));
  if (refs == null) general.push(...new Set([...byRow].filter(([row]) => !sentRows.has(row)).flatMap(([, is]) => is.map((i) => i.message))));
  const rows = lines.map((l): PushRow => {
    const row = l.issue_key == null ? l.row_id : null;
    const inRow = row != null;
    // the row or line is the table's row: each problem without naming it
    const mine = (is: Issue[] | undefined, pick: (i: Issue) => boolean) => [...new Set((is ?? []).filter(pick).map((i) => i.detail))];
    const rowOnes = row == null ? undefined : byRow.get(row);
    const own = byRef.get(l.ref);
    return {
      ref: l.ref,
      key: l.issue_key ?? null,
      label: label(l),
      checks: {
        task: inRow ? mine(rowOnes, (i) => i.field === 'task') : null,
        pic: inRow ? [...new Set(picIssues(rowOnes ?? [], l, slots).map((i) => i.detail))] : null,
        not_yet: mine(own, (i) => i.field === 'not_yet'),
        order: mine(own, (i) => i.field === 'order'),
      },
    };
  });
  return { general, rows, failing: rows.filter((r) => CHECKS.some((c) => (r.checks[c.k]?.length ?? 0) > 0)) };
}
