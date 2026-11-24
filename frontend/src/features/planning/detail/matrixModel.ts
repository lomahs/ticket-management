import type { Schemas } from '../../../shared/api/types';
import type { CellIn, DetailPlanView, MatrixLine } from './api';
import { picIssues, rowSlots, sends } from './pushTable';

type Slot = 'ba_id' | 'bal_id' | 'jm_id';
const SLOT: Record<string, Slot> = { ba: 'ba_id', bal: 'bal_id', jm: 'jm_id' };

const byDayOf = new WeakMap<MatrixLine['cells'], ReadonlyMap<string, number>>();

/**
 * The hours of each day of a line's cells (or its hours logged), once per cells array: the plan keeps a line's cells while they do
 * not change, so each folded level, the total per day and the line itself share one map.
 */
export function hoursByDay(cells: MatrixLine['cells']): ReadonlyMap<string, number> {
  const hit = byDayOf.get(cells);
  if (hit) return hit;
  const out = new Map<string, number>();
  for (const c of cells) out.set(c.day, (out.get(c.day) ?? 0) + c.hours);
  byDayOf.set(cells, out);
  return out;
}

/**
 * The plan with the cells typed and not saved yet laid over it, as the server will save them: a typed day replaces the line's
 * cell (typed by hand), 0 clears it; Planned and Not yet plan follow. A later entry wins. The lines with nothing typed, and the
 * plan itself when nothing is typed for any of its lines, are kept as they are, so what did not change is not drawn again.
 */
export function withTyped(v: DetailPlanView, typed: CellIn[]): DetailPlanView {
  const byRef = new Map<string, Map<string, number>>();
  for (const c of typed) {
    if (c.day) byRef.set(c.ref, (byRef.get(c.ref) ?? new Map<string, number>()).set(c.day, c.hours ?? 0));
  }
  let changed = false;
  const lines = v.lines.map((l) => {
    const days = byRef.get(l.ref);
    if (!days) return l;
    changed = true;
    const cells = [...l.cells.filter((c) => !days.has(c.day)),
      ...[...days].filter(([, h]) => h > 0).map(([day, hours]) => ({ day, hours, auto: false }))]
      .sort((a, b) => a.day.localeCompare(b.day));
    const plan = cells.reduce((a, c) => a + c.hours, 0);
    return { ...l, cells, plan_h: plan, plan_not_yet_h: l.plan_estimate_h - plan };
  });
  return changed ? { ...v, lines } : v;
}

/**
 * What is wrong on a line: its PIC, its Not yet plan, its step order, and the days its PIC is planned over the allocation.
 * `notYetJira`, `orderJira`: the same of a sub-task in Jira the push does not send (nothing changed here), which do not stop it.
 */
export interface LineMarks {
  pic: string[]; notYet: string[]; notYetJira: string[]; order: string[]; orderJira: string[]; overDay: Schemas['DayOverrun'][];
}

const JIRAS = " · Jira's plan: it does not stop the push, changing it here does";

/**
 * The marks of each line, by its ref: the problems on the line (or, for its PIC, on its row's slot, and on every line of the row
 * for a slot none of them has) and the days its PIC is over the allocation. A removed step has no PIC mark; a removed or closed
 * one no day warning.
 */
export function lineMarks(v: DetailPlanView): Record<string, LineMarks> {
  const open = new Map(v.rows.filter((r) => !r.pushed_at).map((r) => [r.id, r]));
  const slots = rowSlots(v.lines);
  const out: Record<string, LineMarks> = {};
  for (const l of v.lines) {
    const slot = l.issue_key == null && l.pic_slot ? SLOT[l.pic_slot] ?? null : null;
    const row = l.row_id == null ? undefined : open.get(l.row_id);
    const pic = (slot && row ? row[slot] : l.pic_id) ?? '';
    const on = (field: string) => v.issues.filter((i) => i.ref === l.ref && i.field === field).map((i) => i.message);
    out[l.ref] = {
      pic: l.pic_slot && !l.removed ? picIssues(v.issues, l, slots).map((i) => i.message) : [],
      notYet: sends(l) ? on('not_yet') : [],
      notYetJira: sends(l) ? [] : on('not_yet').map((m) => m + JIRAS),
      order: sends(l) ? on('order') : [],
      orderJira: sends(l) ? [] : on('order').map((m) => m + JIRAS),
      overDay: l.removed || l.closed ? [] : v.over_day.filter((o) => o.member_id === pic),
    };
  }
  return out;
}
