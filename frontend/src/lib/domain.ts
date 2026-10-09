import type { Fn, LeaveTypeKey, Snapshot, Status, StepKey, Task, Tone } from '../api/types';
import { add, dm, dow } from './dates';

export const STAT: Record<Status, { label: string; v: 'nys' | 'wip' | 'res' | 'clo' }> = {
  N: { label: 'NYS', v: 'nys' }, W: { label: 'WIP', v: 'wip' }, R: { label: 'Resolved', v: 'res' }, C: { label: 'Closed', v: 'clo' },
};
export const TONE_L: Record<Tone, string> = {
  ontime: 'On schedule', early: 'Early start', latestart: 'Late start', delay: 'Delay', nys: 'NYS', res: 'Resolved', clo: 'Closed',
};
export const ROLE_LBL = { BA: 'BA', BAL: 'BA Leader', JM: 'Japan Member · client' } as const;

/** Step colours used for Gantt blocks. */
export const SC: Record<StepKey | 'other', [string, string]> = {
  create: ['Create', 'oklch(0.55 0.14 255)'], balr: ['BAL Review', 'oklch(0.56 0.15 300)'],
  fixb: ['Fix comment BAL', 'oklch(0.62 0.15 50)'], jmr: ['JM Review', 'oklch(0.54 0.12 165)'],
  fixj: ['Fix comment JM', 'oklch(0.58 0.17 20)'], other: ['Other task', 'oklch(0.62 0.02 260)'],
};
export const WEIGHTS = [1, 0.25, 0.3, 0.25, 0.2];

export const mix = (c: string, p: number) => `color-mix(in oklch, ${c} ${p}%, var(--surface-card))`;
export const HT = (c: string) => `repeating-linear-gradient(135deg,${c} 0 1.5px,transparent 1.5px 6px)`;

export function leaveBg(snap: Snapshot, k: LeaveTypeKey | 'holiday') {
  const t = snap.leaveTypes[k];
  if (k === 'holiday') return HT('var(--border-strong)') + ',' + mix(t.c, 22);
  const b = t.tent ? HT(mix(t.c, 70)) + ',' : '';
  return t.half ? b + `linear-gradient(to bottom,${mix(t.c, 45)} 50%,${mix(t.c, 10)} 50%)` : b + mix(t.c, t.tent ? 18 : 38);
}

export interface Av { id: string; ini: string; name: string; color: string; roleLabel: string }
export function av(snap: Snapshot, id: string): Av {
  const m = snap.members[id] ?? snap.members.an;
  return { id, ini: m.name[0], name: m.name, color: m.color, roleLabel: ROLE_LBL[m.role] };
}

export const title = (c: Task) => (c.fid ? `[${c.phase}][${c.fid}] ${c.fname} – ${c.stepLabel}` : c.title ?? '');
export const short = (c: Task) => (c.fid ? `${c.fid} · ${c.stepLabel}` : c.title ?? '');
export const remain = (c: Task) => (c.status === 'C' ? 0 : Math.max(0, c.est - c.logged));

export const allCells = (snap: Snapshot) => snap.functions.flatMap((f) => f.cells);
export const extras = (snap: Snapshot) => snap.tasks.filter((t) => t.kind === 'extra');
export const statics = (snap: Snapshot) => snap.tasks.filter((t) => t.kind === 'static');
/** Everything that appears on the Gantt: matrix sub-tasks, PAY extras/statics and other projects. */
export const planItems = (snap: Snapshot) => [...allCells(snap), ...snap.tasks];
export const findTask = (snap: Snapshot, key: string) => planItems(snap).find((c) => c.key === key);

export function curStatus(f: Fn): Status {
  return f.cur < 5 ? f.cells[f.cur].status : 'C';
}
export function grpStatus(f: Fn) {
  const s = curStatus(f);
  return s === 'R' || s === 'C' ? 'D' : s;
}
export function fState(f: Fn) {
  if (f.cur === 5) return 'done';
  if (f.cells.every((c) => c.status === 'N')) return 'nys';
  if (f.cells.some((c) => c.late)) return 'delay';
  return 'ontime';
}
export function prog(f: Fn, today: string) {
  if (f.cells.some((c) => c.late)) return 'late';
  const exp = f.cells.filter((c) => c.pe < today).length;
  const done = f.cells.filter((c) => c.status === 'C' || c.status === 'R').length;
  return done > exp ? 'early' : 'ontime';
}
export function pct(f: Fn) {
  const w = { C: 1, R: 0.8, W: 0.4, N: 0 };
  return Math.round((f.cells.reduce((a, c) => a + w[c.status], 0) / 5) * 100);
}

/** First and last worklog dates. */
export function logSpan(c: Task): [string, string] | null {
  const ds = c.worklogs.map((w) => w.date).sort();
  return ds.length ? [ds[0], ds[ds.length - 1]] : null;
}

/** Actual start/end used on the Gantt. */
export function actual(c: Task, today: string): [string, string] | null {
  if (c.actS) return [c.actS, c.actE || (c.status === 'W' ? today : c.actS)];
  const ls = logSpan(c);
  if (!ls) return null;
  return [ls[0], c.status === 'W' && ls[1] < today ? today : ls[1]];
}

/** Working-day calendar per member: config days + extra days − holidays − full-day leave. */
export function makeCalendar(snap: Snapshot) {
  const { config, extraDays, holidays, members, leaves, leaveTypes } = snap;
  const lv = (who: string, s: string) => leaves.find((l) => l.who === who && l.date === s);
  const vis = (s: string) => config.days.includes(dow(s)) || !!extraDays[s];
  const off = (s: string, who: string): string | null => {
    if (!config.days.includes(dow(s)) && !extraDays[s]) return 'we';
    const h = holidays[s];
    if (h && members[who] && h[0].includes(members[who].c)) return h[1];
    const l = lv(who, s);
    if (l && leaveTypes[l.type].full) return members[who].name + ' · ' + leaveTypes[l.type].label;
    return null;
  };
  const nextWork = (s: string, who: string, sk?: string[]) => {
    for (;;) {
      const r = off(s, who);
      if (!r) return s;
      if (r !== 'we' && sk) sk.push(dm(s) + ' ' + r);
      s = add(s, 1);
    }
  };
  const span = (s: string, n: number, who: string, sk?: string[]): [string, string] => {
    const st = nextWork(s, who, sk);
    let e = st;
    for (let i = 1; i < n; i++) e = nextWork(add(e, 1), who, sk);
    return [st, e];
  };
  const isHoliday = (s: string, who: string) => {
    const h = holidays[s];
    return h && h[0].includes(members[who].c) ? h : null;
  };
  return { lv, vis, off, span, isHoliday };
}
export type Cal = ReturnType<typeof makeCalendar>;

export const dayHead = (s: string, today: string, extraDays: Record<string, string>) => {
  const t = s === today;
  const ex = extraDays[s];
  return {
    fg: t ? 'var(--text-heading)' : ex ? SC.create[1] : 'var(--text-muted)',
    bg: t ? 'var(--surface-tint)' : ex ? mix(SC.create[1], 8) : 'transparent',
    fw: t || ex ? 700 : 500,
    tip: ex ? 'Extra working day · ' + ex : '',
  };
};
