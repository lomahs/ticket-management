import type { Schemas } from '../../../shared/api/types';

/** Display-side filtering of the matrix rows the API returns; the state lives in the URL. */

type Row = Schemas['FunctionRow'];
export interface Range { from?: string; to?: string }
export interface MatrixFilter {
  q: string;
  members: string[];
  status: string[]; // summary groups N | W | D
  prog: string[]; // early | ontime | late
  ranges: Record<number, Range>; // per step index
  sort: { i: number; dir: 1 | -1 } | null;
}
export type MatrixSearch = Partial<Record<'q' | 'members' | 'status' | 'prog' | 'sort' | 'r0' | 'r1' | 'r2' | 'r3' | 'r4', string>>;

const list = (s?: string) => (s ? s.split(',').filter(Boolean) : []);

export function fromSearch(s: MatrixSearch): MatrixFilter {
  const ranges: Record<number, Range> = {};
  for (let i = 0; i < 5; i++) {
    const raw = s[`r${i}` as keyof MatrixSearch];
    if (!raw) continue;
    const [from, to] = raw.split('..');
    ranges[i] = { ...(from ? { from } : {}), ...(to ? { to } : {}) };
  }
  const m = s.sort?.match(/^(\d):(asc|desc)$/);
  return {
    q: s.q ?? '', members: list(s.members), status: list(s.status), prog: list(s.prog), ranges,
    sort: m ? { i: Number(m[1]), dir: m[2] === 'asc' ? 1 : -1 } : null,
  };
}

export function toSearch(f: MatrixFilter): MatrixSearch {
  const s: MatrixSearch = {};
  if (f.q) s.q = f.q;
  if (f.members.length) s.members = f.members.join(',');
  if (f.status.length) s.status = f.status.join(',');
  if (f.prog.length) s.prog = f.prog.join(',');
  if (f.sort) s.sort = `${f.sort.i}:${f.sort.dir === 1 ? 'asc' : 'desc'}`;
  for (const [i, r] of Object.entries(f.ranges)) if (r.from || r.to) s[`r${i}` as keyof MatrixSearch] = `${r.from ?? ''}..${r.to ?? ''}`;
  return s;
}

export const anyFilter = (f: MatrixFilter) => !!(f.q || f.members.length || f.status.length || f.prog.length || Object.keys(f.ranges).length);

export const matchesText = (r: Row, q: string) => !q || `${r.id} ${r.name}`.toLowerCase().includes(q.trim().toLowerCase());

/**
 * The single tickets the filters keep: text and member filters apply to them; status, progress and date filters are about
 * the 5 steps, so while one is on no single ticket is shown.
 */
export function filterTickets(tickets: Schemas['MatrixTicket'][], f: MatrixFilter): Schemas['MatrixTicket'][] {
  if (f.status.length || f.prog.length || Object.keys(f.ranges).length) return [];
  const q = f.q.trim().toLowerCase();
  return tickets.filter((t) => (!q || `${t.card.key} ${t.name}`.toLowerCase().includes(q))
    && (!f.members.length || f.members.includes(t.card.assignee_id ?? '')));
}

export function applyFilter(rows: Row[], f: MatrixFilter): Row[] {
  let out = rows.filter((r) =>
    matchesText(r, f.q)
    && (!f.members.length || f.members.includes(r.ba_id ?? '') || f.members.includes(r.bal_id ?? ''))
    && (!f.status.length || f.status.includes(r.group))
    && (!f.prog.length || f.prog.includes(r.progress))
    && Object.entries(f.ranges).every(([i, rg]) => {
      const c = r.cells[Number(i)];
      if (!c?.planned_start || !c.planned_end) return false;
      return (!rg.from || c.planned_end >= rg.from) && (!rg.to || c.planned_start <= rg.to);
    }));
  if (f.sort) {
    const { i, dir } = f.sort;
    const key = (r: Row) => r.cells[i]?.planned_start ?? '9999';
    out = [...out].sort((a, b) => (key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0) * dir);
  }
  return out;
}

export interface SummaryRow { memberId: string | null; role: 'BA' | 'BAL' | ''; counts: Record<'N' | 'W' | 'D' | 'late', number>; total: number }

/** Functions by current-step group per BA and BAL of the function, plus an "All" row. */
export function summaryCounts(rows: Row[], bas: string[], bals: string[]): SummaryRow[] {
  const count = (list: Row[]) => ({
    N: list.filter((r) => r.group === 'N').length, W: list.filter((r) => r.group === 'W').length,
    D: list.filter((r) => r.group === 'D').length, late: list.filter((r) => r.progress === 'late').length,
  });
  const mk = (memberId: string | null, role: SummaryRow['role'], list: Row[]): SummaryRow => ({ memberId, role, counts: count(list), total: list.length });
  return [
    ...bas.map((id) => mk(id, 'BA', rows.filter((r) => r.ba_id === id))),
    ...bals.map((id) => mk(id, 'BAL', rows.filter((r) => r.bal_id === id))),
    mk(null, '', rows),
  ];
}

export function stepRange(rows: Row[], i: number): [string, string] | null {
  const cs = rows.map((r) => r.cells[i]).filter((c): c is Schemas['TaskCard'] => !!c?.planned_start && !!c.planned_end);
  if (!cs.length) return null;
  return [cs.reduce((m, c) => (c.planned_start! < m ? c.planned_start! : m), cs[0].planned_start!),
    cs.reduce((m, c) => (c.planned_end! > m ? c.planned_end! : m), cs[0].planned_end!)];
}

/** Functions waiting at each step; a function whose steps are all done (3 or 5 of them) waits nowhere. */
export function pendingAtStep(rows: Row[]) {
  const per = [0, 1, 2, 3, 4].map((i) => rows.filter((r) => r.current === i && r.current < r.cells.length));
  const max = Math.max(...per.map((l) => l.length));
  return per.map((l, i) => ({ count: l.length, late: l.filter((r) => r.cells[i]?.late).length, bottleneck: max > 0 && l.length === max }));
}
