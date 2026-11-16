import { useEffect, useRef, useState, type ReactNode } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useMeta } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button, DateField, Icon, IconButton, type IconName } from '../../../shared/ds';
import { dm, f2, WDN } from '../../../shared/format/dates';
import { STEPS } from '../../../shared/format/labels';
import { useToast } from '../../../shared/state/toast';
import { useEditJiraLine, useEditStep, useSaveCells, useShiftPlan, type DetailPlanView, type MatrixLine } from './api';
import { usePersonName, type Person } from './DetailPlan';
import { checkText } from './PlanSummary';
import { PushFlow } from './PushControls';

type Row = Schemas['EstimationRow'];
export type Slot = 'ba_id' | 'bal_id' | 'jm_id';

/** Not yet within a hundredth counts as planned, as the push sees it. */
const NOT_YET_EPS = 0.01;
const step = (k?: string | null) => STEPS.find((s) => s.k === k);
/** Hours as they are typed: "8", "1.5"; nothing for none. */
const h = (n: number) => (Math.abs(n) < 0.005 ? '' : String(Number(f2(n))));
/** A sum on a folded row: "–" for none. */
const sumText = (n: number) => (Math.abs(n) < 0.005 ? '–' : f2(n));
const sum = (cells: MatrixLine['cells'], day: string) => cells.filter((c) => c.day === day).reduce((a, c) => a + c.hours, 0);
const weekday = (d: string) => WDN[new Date(`${d}T00:00:00Z`).getUTCDay()];
const plural = (n: number, w: string) => `${n} ${w}${n === 1 ? '' : 's'}`;
const SLOT: Record<string, Slot> = { ba: 'ba_id', bal: 'bal_id', jm: 'jm_id' };
/** Whether a line still has hours to plan, as the push sees it. */
const left = (l: MatrixLine) => Math.abs(l.plan_not_yet_h) > NOT_YET_EPS && !l.removed && !l.closed;

/**
 * Hours typed in a box: sent once the box is left (or Enter), only when they changed; what was typed stays on screen until the
 * plan comes back. A cleared box is 0 when {@code blank} is 0, else it is put back.
 */
function HoursInput({ label, value, blank, send, className }: {
  label: string; value: number; blank: 'zero' | 'keep'; send: (hours: number) => Promise<unknown>; className?: string;
}) {
  const [draft, setDraft] = useState<string | null>(null);
  const shown = draft ?? (blank === 'zero' ? h(value) : String(Number(f2(value))));
  const leave = () => {
    if (draft == null) return;
    const t = draft.trim();
    const n = t === '' && blank === 'zero' ? 0 : Number(t);
    if (t === '' && blank === 'keep') { setDraft(null); return; }
    if (!Number.isFinite(n) || n < 0 || Math.abs(n - value) < 0.005) { setDraft(null); return; }
    const sent = draft;
    // what was typed again meanwhile stays until it is left in turn
    void send(Math.round(n * 100) / 100).finally(() => setDraft((d) => (d === sent ? null : d)));
  };
  return (
    <input type="number" min={0} step={0.5} aria-label={label} value={shown} onChange={(e) => setDraft(e.target.value)}
      onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); }} onBlur={leave}
      className={`pm-in${shown === '' ? ' empty' : ''}${className ? ` ${className}` : ''}`} />
  );
}

/** Where a line's step comes in its function (a ticket first). */
const stepOrder = (l: MatrixLine) => (l.step ? STEPS.findIndex((x) => x.k === l.step) + 1 : 0);

/** A function of the plan: a row not in Jira yet with its steps, or a function of the sub-tasks already in Jira. */
interface Group { key: string; name: string; row?: Row; lines: MatrixLine[] }
/** A Task of a phase (its sub-phase) and the functions under it. */
interface Sub { key: string; name: string; groups: Group[] }

type Status = 'planned' | 'notyet' | 'blocked';
const STATUS: [Status, string][] = [['planned', 'Planned'], ['notyet', 'Not yet planned'], ['blocked', 'Blocks the push']];

/** A pill that opens a list of choices to tick, with Unselect all; it closes on Escape or a click outside. */
function FilterMenu({ label, icon, picked, options, toggle, clear, wide, children }: {
  label: string; icon?: IconName; picked: number; options?: [string, string, string][]; toggle?: (v: string) => void; clear?: () => void;
  wide?: boolean; children?: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    if (!open) return;
    const out = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false); };
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', out);
    document.addEventListener('keydown', key);
    return () => { document.removeEventListener('mousedown', out); document.removeEventListener('keydown', key); };
  }, [open]);
  return (
    <span ref={box} className="pm-dd">
      <button type="button" className={`pm-pill${picked ? ' on' : ''}`} aria-haspopup="true" aria-expanded={open} onClick={() => setOpen((x) => !x)}>
        {icon && <Icon name={icon} size={13} />}{label}{picked > 0 && <span>{` (${picked})`}</span>}<Icon name="chevron-down" size={12} />
      </button>
      {open && (
        <div className={`pm-menu${wide ? ' wide' : ''}`} role="group" aria-label={label}>
          {clear && <button type="button" className="pm-unsel" disabled={!picked} onClick={clear}>Unselect all</button>}
          {options?.map(([v, text, on]) => (
            <label key={v} className="pm-opt">
              <input type="checkbox" checked={on === 'on'} onChange={() => toggle?.(v)} />{text}
            </label>
          ))}
          {children}
        </div>
      )}
    </span>
  );
}

/**
 * The plan as a matrix, in four levels: phase › sub-phase (the Task) › function › step (one line per sub-task), with each step's
 * PIC, Plan Estimation, Planned, Not yet plan and dates, then the hours planned on each day. A level folds; folded, it shows the
 * hours of its steps per day. A row not in Jira yet picks its people on its steps' PIC cells (the step's PIC by Settings › Step
 * weights, the JM on JM Review), which set the function's BA, BAL or JM; a sub-task in Jira changes its PIC and is kept until the
 * push. Every day can be planned, past ones too (a sub-task in Jira keeps the past day typed here until the push); the hours
 * logged show under the plan.
 *
 * The figures are the planning ones, no actuals: Plan Estimation (the Original Estimate), Planned (every day's hours) and Not
 * yet plan; what is left to plan blocks the push and is counted in the problems above.
 */
export function PlanMatrix({ epic, v, people, admin, assign, swap }: {
  epic: string; v: DetailPlanView; people: Person[]; admin: boolean;
  assign: (r: Row, slot: Slot, id: string | null) => void; swap: (a: number, b: number) => void;
}) {
  const toast = useToast();
  const today = useMeta().data?.today ?? '';
  const edit = useEditStep(epic);
  const cells = useSaveCells(epic);
  const jira = useEditJiraLine(epic);
  const shift = useShiftPlan(epic);
  // the functions picked to shift (group keys), and by how many working days which way
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [by, setBy] = useState('1');
  const [way, setWay] = useState<'later' | 'earlier'>('later');
  const [force, setForce] = useState(false);
  const [folded, setFolded] = useState<Set<string>>(new Set());
  const [q, setQ] = useState('');
  const [pics, setPics] = useState<Set<string>>(new Set());
  const [statuses, setStatuses] = useState<Set<string>>(new Set());
  const [stories, setStories] = useState<Set<string>>(new Set());
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [legend, setLegend] = useState(false);
  const scroller = useRef<HTMLDivElement>(null);
  const search = useRef<HTMLInputElement>(null);
  const personName = usePersonName(people);
  const name = (id?: string | null) => (id ? personName(id) : '');
  const open = v.rows.filter((r) => !r.pushed_at);
  const fail = (e: unknown) => toast(errorMessage(e), true);
  const sendStep = (l: MatrixLine, p: { effort_h?: number | null; removed?: boolean }) => {
    if (l.row_id == null) return Promise.resolve();
    return edit.mutateAsync({ row_id: l.row_id, step: l.step, effort_h: l.edited ? l.total_h : null, removed: l.removed, ...p }).catch(fail);
  };
  const sendCell = (l: MatrixLine, day: string, hours: number) => cells.mutateAsync([{ ref: l.ref, day, hours }]).catch(fail);
  const sendJira = (key: string, total: number | null, pic: string | null) => jira.mutateAsync({ key, total_h: total, pic_id: pic }).catch(fail);

  // Ctrl / Cmd + K: the search
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); search.current?.focus(); search.current?.select(); }
    };
    document.addEventListener('keydown', key);
    return () => document.removeEventListener('keydown', key);
  }, []);

  // phases in the master schedule's order, then any other; in each, its Tasks; in each, the rows not in Jira yet, then the functions in Jira
  const phases = [...new Set([...v.checks.map((c) => c.phase), ...open.map((r) => r.phase), ...v.lines.map((l) => l.phase)])]
    .filter((p) => open.some((r) => r.phase === p) || v.lines.some((l) => l.phase === p));
  const subsOf = (p: string): Sub[] => {
    // a row's steps already in Jira (linked_row_id) show in its function, in step order with the steps still to create
    const groups: [string, Group][] = open.filter((r) => r.phase === p)
      .map((r) => [r.subphase, { key: `row:${r.id}`, name: r.function_name, row: r,
        lines: v.lines.filter((l) => l.row_id === r.id || l.linked_row_id === r.id).sort((a, b) => stepOrder(a) - stepOrder(b)) }]);
    // a function in Jira is its name under its Task, as in the Function Matrix: the same name under another Task is another one
    const inJira = new Map<string, [string, Group]>();
    for (const l of v.lines) {
      if (l.issue_key == null || l.phase !== p || open.some((r) => r.id === l.linked_row_id)) continue;
      const id = `${l.subphase}\u0000${l.function}`;
      const g = inJira.get(id) ?? [l.subphase, { key: `jira:${p}:${l.subphase}:${l.function}`, name: l.function, lines: [] }];
      g[1].lines.push(l);
      inJira.set(id, g);
    }
    const subs = new Map<string, Sub>();
    for (const [s, g] of [...groups, ...inJira.values()]) {
      const sub = subs.get(s) ?? { key: `sub:${p}:${s}`, name: s || 'No Task', groups: [] };
      sub.groups.push(g);
      subs.set(s, sub);
    }
    return [...subs.values()];
  };
  const tree = phases.map((p) => ({ phase: p, subs: subsOf(p) }));
  const allGroups = tree.flatMap((t) => t.subs.flatMap((s) => s.groups));

  // a row's step picks the function's person in its slot; a sub-task in Jira its own PIC
  const slotOf = (l: MatrixLine): Slot | null => (l.issue_key == null && l.pic_slot ? SLOT[l.pic_slot] ?? null : null);
  const rowOf = (l: MatrixLine) => open.find((r) => r.id === l.row_id);
  const picOf = (l: MatrixLine) => { const s = slotOf(l); const r = rowOf(l); return (s && r ? r[s] : l.pic_id) ?? ''; };

  // the filters
  const filtering = !!q.trim() || pics.size > 0 || statuses.size > 0 || stories.size > 0 || !!from || !!to;
  const needle = q.trim().toLowerCase();
  const passes = (l: MatrixLine, g: Group, sub: Sub) => {
    if (needle && ![l.summary, step(l.step)?.short ?? '', g.name, sub.name, l.issue_key ?? ''].some((t) => t.toLowerCase().includes(needle))) return false;
    if (pics.size && !pics.has(picOf(l))) return false;
    if (statuses.size) {
      const st: Record<Status, boolean> = { planned: l.plan_h > 0.005, notyet: left(l), blocked: left(l) || (l.out_of_order && !l.removed) };
      if (![...statuses].some((s) => st[s as Status])) return false;
    }
    return !stories.size || stories.has(g.key);
  };
  const days = v.window.filter((d) => (!from || d >= from) && (!to || d <= to));
  const shownLines = (g: Group, sub: Sub) => (filtering ? g.lines.filter((l) => passes(l, g, sub)) : g.lines);
  const total = v.lines.length;
  const matched = tree.reduce((a, t) => a + t.subs.reduce((b, s) => b + s.groups.reduce((c, g) => c + shownLines(g, s).length, 0), 0), 0);
  const toggleIn = (set: (f: (s: Set<string>) => Set<string>) => void) => (x: string) =>
    set((s) => { const n = new Set(s); if (n.has(x)) n.delete(x); else n.add(x); return n; });
  const clearAll = () => { setQ(''); setPics(new Set()); setStatuses(new Set()); setStories(new Set()); setFrom(''); setTo(''); };

  const togglePick = toggleIn(setPicked);
  // picks are functions (group keys) and steps (line refs); one no longer in the plan (pushed, removed) is forgotten
  const pickedGroups = allGroups.filter((g) => picked.has(g.key));
  const pickedSteps = v.lines.filter((l) => picked.has(l.ref) && !l.removed && !l.closed);
  const shiftRefs = [...new Set([...pickedGroups.flatMap((g) => g.lines), ...pickedSteps].filter((l) => !l.removed && !l.closed).map((l) => l.ref))];
  const pickedText = [pickedGroups.length > 0 && plural(pickedGroups.length, 'function'), pickedSteps.length > 0 && plural(pickedSteps.length, 'step')]
    .filter(Boolean).join(', ');
  const shiftBy = Math.min(366, Math.max(1, Math.round(Number(by)) || 1));
  const shiftPicked = () => {
    shift.mutateAsync({ refs: shiftRefs, days: way === 'later' ? shiftBy : -shiftBy }).then(() => {
      toast(`Shifted ${pickedText} ${plural(shiftBy, 'working day')} ${way}`);
      setPicked(new Set());
    }, fail);
  };

  const fold = (k: string) => setFolded((f) => { const n = new Set(f); if (n.has(k)) n.delete(k); else n.add(k); return n; });
  const toToday = () => {
    const sc = scroller.current;
    const th = sc?.querySelector<HTMLElement>('th.pm-today');
    const edge = sc?.querySelector<HTMLElement>('thead .pm-c4');
    if (sc && th && edge) sc.scrollTo({ left: Math.max(0, th.offsetLeft - edge.offsetLeft - edge.offsetWidth - 8), behavior: 'smooth' });
  };

  // BA and BAL: anyone but a JM; JM: anyone but a BA or a BAL; whoever is picked stays listed
  const choices = (slot: Slot, picked?: string | null) => people.filter((m) => m.id === picked
    || (slot === 'jm_id' ? m.role !== 'BA' && m.role !== 'BAL' : m.role !== 'JM'));
  // a person picked by hand, not by Auto allocate: "Clear, keep my input" keeps them
  const byHand = (r: Row, slot: Slot) => {
    const x = v.by_hand.find((y) => y.row_id === r.id);
    return !!x && (slot === 'ba_id' ? x.ba : slot === 'bal_id' ? x.bal : x.jm);
  };

  /** The day cells of a folded level: its steps' hours per day; empty while it is open. */
  const sums = (lines: MatrixLine[], closed: boolean) => days.map((d) => {
    const t = closed ? lines.filter((l) => !l.removed).reduce((a, l) => a + sum(l.cells, d), 0) : 0;
    return <td key={d} className={`pm-cell pm-sum${d === today ? ' pm-today' : ''}${closed && t < 0.005 ? ' zero' : ''}`}>{closed ? sumText(t) : ''}</td>;
  });
  const linesOf = (gs: Group[]) => gs.flatMap((g) => g.lines);
  const foldButton = (k: string, what: string) => {
    const closed = folded.has(k);
    return (
      <button type="button" className={`pm-fold${closed ? ' closed' : ''}`} aria-expanded={!closed}
        aria-label={`${closed ? 'Show' : 'Hide'} ${what}`} title={`${closed ? 'Show' : 'Hide'} ${what}`} onClick={() => fold(k)}>
        <Icon name="chevron-down" size={14} />
      </button>
    );
  };

  const groupRow = (g: Group, gs: Group[]) => {
    const r = g.row;
    const closed = folded.has(g.key);
    const rows = gs.filter((x) => x.row);
    const i = rows.indexOf(g);
    const prev = rows[i - 1]?.row;
    const next = rows[i + 1]?.row;
    const live = g.lines.filter((l) => !l.removed);
    return (
      <tr key={g.key} className="pm-fn" aria-label={`Function ${g.name}`}>
        <th scope="row" className="pm-c0">
          <span className="pm-fnh">
            {admin && <input type="checkbox" className="pm-pick" aria-label={`Pick ${g.name}`} title="Pick to shift its plan"
              checked={picked.has(g.key)} onChange={() => togglePick(g.key)} />}
            {foldButton(g.key, `the steps of ${g.name}`)}
            <span className="pm-fn-name ellipsis" title={g.name}>{g.name}</span>
            {!r && <span className="pm-badge">In Jira</span>}
            <span className="spacer" />
            {r && (
              <span className="pm-order" role="group" aria-label={`Order of ${g.name}`} title="Auto allocate plans the functions top to bottom">
                <Icon name="grip-vertical" size={13} />
                <button type="button" className="pm-ob" aria-label={`Move ${r.function_name} up`} title="Move up: Auto allocate plans it earlier"
                  disabled={!admin || !prev} onClick={() => { if (prev) swap(r.id, prev.id); }}><Icon name="arrow-up" size={13} /></button>
                <button type="button" className="pm-ob" aria-label={`Move ${r.function_name} down`} title="Move down: Auto allocate plans it later"
                  disabled={!admin || !next} onClick={() => { if (next) swap(r.id, next.id); }}><Icon name="arrow-down" size={13} /></button>
              </span>
            )}
          </span>
        </th>
        <td className="pm-c1" />
        <td className="pm-c2">{closed ? sumText(live.reduce((a, l) => a + l.plan_estimate_h, 0)) : ''}</td>
        <td className="pm-c3">{closed ? sumText(live.reduce((a, l) => a + l.plan_h, 0)) : ''}</td>
        <td className="pm-c4">{closed ? sumText(live.filter((l) => !l.closed).reduce((a, l) => a + l.plan_not_yet_h, 0)) : ''}</td>
        <td className="pm-c5" /><td className="pm-c6" /><td className="pm-c7" />
        {sums(g.lines, closed)}
      </tr>
    );
  };

  const line = (l: MatrixLine) => {
    const st = step(l.step);
    const inJira = l.issue_key != null;
    const key = l.issue_key ?? '';
    const live = admin && !l.removed && !l.closed; // its Plan Estimation, PIC and days can change
    const slot = slotOf(l);
    const r = rowOf(l);
    let pic: ReactNode;
    if (!inJira && slot && r && admin && !l.removed) {
      const hand = byHand(r, slot);
      pic = (
        <select className="pm-pic" aria-label={`PIC, ${l.summary}`} value={r[slot] ?? ''} onChange={(e) => assign(r, slot, e.target.value || null)}
          data-by-hand={hand || undefined} data-empty={!r[slot] || undefined}
          title={hand ? 'Picked by hand: Clear, keep my input keeps it' : slot === 'jm_id' ? 'The JM who reviews the function' : undefined}>
          <option value="">–</option>
          {choices(slot, r[slot]).map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
        </select>
      );
    } else if (inJira && live) {
      const now = l.pic_id;
      const options = !now || people.some((p) => p.id === now) ? people : [...people, { id: now, name: name(now) }];
      pic = (
        <select className="pm-pic" aria-label={`PIC, ${l.summary}`} value={l.pic_id ?? ''} onChange={(e) => { if (e.target.value) void sendJira(key, null, e.target.value); }}>
          {!l.pic_id && <option value="">–</option>}
          {options.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
      );
    } else {
      pic = <span aria-label={`PIC, ${l.summary}`}>{name(slot && r ? r[slot] : l.pic_id) || '–'}</span>;
    }
    // a sub-task's Plan Estimation is its Original Estimate in Jira: changed there, not here
    const estimate = live && !inJira
      ? <HoursInput label={`Plan Estimation, ${l.summary}`} value={l.total_h} blank="keep" send={(n) => sendStep(l, { effort_h: n })} className={`pm-in-est${l.edited ? ' hand' : ''}`} />
      : <span aria-label={`Plan Estimation, ${l.summary}`}>{f2(l.plan_estimate_h)}</span>;
    return (
      <tr key={l.ref} className="pm-step" aria-label={l.summary} data-out-of-order={l.out_of_order || undefined} data-removed={l.removed || undefined}
        title={l.out_of_order ? 'Starts before the step before it ends: push is blocked until it is fixed' : undefined}>
        <th scope="row" className="pm-c0">
          <span className="pm-stp">
            {admin && !l.removed && !l.closed && <input type="checkbox" className="pm-pick" aria-label={`Pick ${l.summary}`}
              title="Pick to shift or push this step" checked={picked.has(l.ref)} onChange={() => togglePick(l.ref)} />}
            <span className={`pm-key${inJira ? '' : ' new'}`} title={inJira ? `Sub-task ${l.issue_key} in Jira` : 'Not in Jira yet: the push creates it'}>
              {inJira ? l.issue_key : 'New'}
            </span>
            <span className="pm-stp-name ellipsis" title={l.summary}>{st?.short ?? l.summary}</span>
            {l.pending && <span className="status-pill" style={{ background: 'var(--hl-amber)', color: 'var(--hl-amber-fg)' }}
              title="Changed here: sent to Jira with the push">Not pushed</span>}
            {l.closed && <span className="status-pill">Closed</span>}
            <span className="spacer" />
            {!inJira && admin && l.edited && !l.removed && <IconButton icon="undo-2" label={`Reset ${l.summary}`} size="sm"
              title="Back to the effort of the estimation" onClick={() => void sendStep(l, { effort_h: null })} style={{ width: 26, height: 26 }} />}
            {!inJira && admin && !l.removed && (
              <button type="button" className="pm-del" aria-label={`Remove ${l.summary}`} title="Remove this step" onClick={() => void sendStep(l, { removed: true })}>
                <Icon name="trash-2" size={14} />
              </button>
            )}
            {!inJira && l.removed && <Button appearance="subtle" size="sm" aria-label={`Restore ${l.summary}`} disabled={!admin}
              onClick={() => void sendStep(l, { removed: false })}>Restore</Button>}
          </span>
        </th>
        <td className="pm-c1">{pic}</td>
        <td className="pm-c2">{estimate}</td>
        <td className="pm-c3" aria-label={`Planned, ${l.summary}`}>{f2(l.plan_h)}</td>
        <td className="pm-c4" aria-label={`Not yet plan, ${l.summary}`} data-left={left(l) || undefined}>{f2(l.plan_not_yet_h)}</td>
        <td className="pm-c5" aria-label={`Start, ${l.summary}`}>{l.start ? dm(l.start) : '–'}</td>
        <td className="pm-c6" aria-label={`End, ${l.summary}`}>{l.end ? dm(l.end) : '–'}</td>
        <td className="pm-c7" aria-label={`Actual remaining, ${l.summary}`}>{l.actual_remaining_h == null ? '–' : f2(l.actual_remaining_h)}</td>
        {days.map((d) => {
          const planned = sum(l.cells, d);
          const logged = sum(l.logged, d);
          // every day of an open line can be planned, past ones too; the hours logged show under the plan
          const locked = !live;
          const typed = !inJira && l.cells.some((c) => c.day === d && !c.auto && c.hours > 0);
          return (
            <td key={d} data-day={d} data-out-of-order={(l.out_of_order && !l.removed) || undefined}
              className={`pm-cell${d === today ? ' pm-today' : ''}${locked ? ' locked' : ''}${l.out_of_order && !l.removed ? ' red' : ''}`}>
              {locked
                ? <span className="pm-lk">{h(planned)}</span>
                : <HoursInput label={`${l.summary} ${d}`} value={planned} blank="zero" send={(n) => sendCell(l, d, n)} className={typed ? 'hand' : undefined} />}
              {logged > 0 && <span className="muted pm-logged" title={`${h(logged)}h logged`}>{`${h(logged)}h`}</span>}
            </td>
          );
        })}
      </tr>
    );
  };

  const body: ReactNode[] = [];
  for (const { phase: p, subs } of tree) {
    const c = v.checks.find((x) => x.phase === p);
    const vis = subs.map((s) => ({ s, gs: s.groups.map((g) => ({ g, ls: shownLines(g, s) })).filter((x) => !filtering || x.ls.length) }))
      .filter((x) => !filtering || x.gs.length);
    if (filtering && !vis.length) continue;
    const pClosed = folded.has(`phase:${p}`);
    const fns = subs.reduce((a, s) => a + s.groups.length, 0);
    body.push(
      <tr key={`phase:${p}`} className="pm-phase" aria-label={`Phase ${p}`}>
        <th scope="row" className="pm-c0">
          <span className="pm-ph">{foldButton(`phase:${p}`, `the functions of ${p}`)}<span className="pm-ph-name">{p}</span><span className="pm-badge">{plural(fns, 'function')}</span></span>
        </th>
        <td colSpan={4} className={`pm-meta${c && c.plan_end && c.days_late > 0 ? ' late' : ''}`}>
          {c ? checkText(c).replace(`${p} `, '').replace(`${p}: `, '') : ''}
        </td>
        <td className="pm-c5" /><td className="pm-c6" /><td className="pm-c7" />
        {sums(linesOf(subs.flatMap((s) => s.groups)), pClosed)}
      </tr>,
    );
    if (pClosed) continue;
    for (const { s, gs } of vis) {
      const sClosed = folded.has(s.key);
      body.push(
        <tr key={s.key} className="pm-sub" aria-label={`Sub-phase ${s.name}`}>
          <th scope="row" className="pm-c0">
            <span className="pm-sb">{foldButton(s.key, `the functions of ${s.name}`)}<Icon name="layers" size={13} color="var(--pm-today)" />
              <span className="pm-sb-name ellipsis" title={s.name}>{s.name}</span><span className="pm-badge">{plural(s.groups.length, 'function')}</span></span>
          </th>
          <td colSpan={4} className="pm-meta">{`${f2(linesOf(s.groups).filter((l) => !l.removed).reduce((a, l) => a + l.plan_estimate_h, 0))}h estimated`}</td>
          <td className="pm-c5" /><td className="pm-c6" /><td className="pm-c7" />
          {sums(linesOf(s.groups), sClosed)}
        </tr>,
      );
      if (sClosed) continue;
      gs.forEach(({ g, ls }) => {
        body.push(groupRow(g, s.groups));
        if (!folded.has(g.key)) body.push(...ls.map(line));
      });
    }
  }
  const footLines = tree.flatMap((t) => t.subs.flatMap((s) => s.groups.flatMap((g) => shownLines(g, s)))).filter((l) => !l.removed);
  const storyOptions = tree.flatMap((t) => t.subs.flatMap((s) => s.groups.map((g): [string, string, string] => [g.key, `${s.name} · ${g.name}`, stories.has(g.key) ? 'on' : ''])));
  const picOptions: [string, string, string][] = [['', 'Unassigned', pics.has('') ? 'on' : ''],
    ...people.map((m): [string, string, string] => [m.id, m.name, pics.has(m.id) ? 'on' : ''])];

  return (
    <section className="card pm" aria-label="Detail plan matrix">
      <div className="pm-bar">
        <span className="pm-bar-t">Plan by day</span>
        <span className="pm-info" tabIndex={0} role="button" aria-label="How to read the plan" aria-expanded={legend}
          onMouseEnter={() => setLegend(true)} onMouseLeave={() => setLegend(false)} onFocus={() => setLegend(true)} onBlur={() => setLegend(false)}>
          <Icon name="info" size={16} />
          {legend && <span className="pm-tip" role="tooltip">
            <span className="pm-tip-h">How to read the plan</span>
            <span className="pm-tip-r"><span className="pm-k hand">4</span><span><b>Typed by hand</b>Auto allocate keeps it.</span></span>
            <span className="pm-tip-r"><span className="pm-k red"><Icon name="triangle-alert" size={12} /></span><span><b>Out of step order</b>Starts before the step before it ends: blocks the push.</span></span>
            <span className="pm-tip-r"><span className="pm-k">2.00</span><span><b>Not yet plan</b>Hours of the Plan Estimation not planned on a day yet: blocks the push.</span></span>
            <span className="pm-tip-r"><span className="pm-k lock">1.5h</span><span><b>Logged</b>The hours logged on a day show under its plan; a past day can be planned too.</span></span>
            <span className="pm-tip-r"><span className="pm-k today">{today ? Number(today.slice(8)) : ''}</span><span><b>Today</b>The framed column.</span></span>
          </span>}
        </span>
        <span className="spacer" />
        {allGroups.length > 0 && (
          <span className="pm-tools" role="group" aria-label="Plan view">
            <button type="button" className="pm-tb" onClick={() => setFolded(new Set())}><Icon name="chevrons-up-down" size={14} />Expand all</button>
            <button type="button" className="pm-tb" onClick={() => setFolded(new Set(allGroups.map((g) => g.key)))}><Icon name="chevrons-down-up" size={14} />Collapse all</button>
            <span className="pm-tsep" aria-hidden="true" />
            <button type="button" className="pm-tb today" onClick={toToday} title="Scroll the days back to today"><Icon name="locate-fixed" size={14} />Today</button>
          </span>
        )}
      </div>
      <div className="pm-filters" role="search" aria-label="Filter the plan">
        <label className="pm-search">
          <Icon name="search" size={14} />
          <input ref={search} type="search" placeholder="Search steps or functions" aria-label="Search steps or functions" value={q} onChange={(e) => setQ(e.target.value)} />
          <kbd>Ctrl K</kbd>
        </label>
        <FilterMenu label="PIC" picked={pics.size} options={picOptions} toggle={toggleIn(setPics)} clear={() => setPics(new Set())} />
        <FilterMenu label="Status" picked={statuses.size} options={STATUS.map(([k, t]): [string, string, string] => [k, t, statuses.has(k) ? 'on' : ''])}
          toggle={toggleIn(setStatuses)} clear={() => setStatuses(new Set())} />
        <FilterMenu label="Function" picked={stories.size} options={storyOptions} toggle={toggleIn(setStories)} clear={() => setStories(new Set())} />
        <FilterMenu label="More filters" icon="sliders-horizontal" picked={(from ? 1 : 0) + (to ? 1 : 0)} wide>
          <span className="pm-mh"><Icon name="calendar" size={13} />Days shown</span>
          <DateField size="sm" label="From" value={from} onChange={setFrom} min={v.window[0]} max={to || v.window.at(-1)} />
          <DateField size="sm" label="To" value={to} onChange={setTo} min={from || v.window[0]} max={v.window.at(-1)} />
        </FilterMenu>
        {filtering && <span className="pm-count" aria-live="polite">{`${matched} of ${total} steps`}</span>}
        <span className="spacer" />
        {filtering && <button type="button" className="pm-clear" onClick={clearAll}><Icon name="rotate-ccw" size={13} />Clear all</button>}
      </div>
      {shiftRefs.length > 0 && (
        <div className="pm-shift" role="group" aria-label="Picked functions and steps">
          <span className="pm-count">{`${pickedText} selected`}</span>
          <span>Shift</span>
          <input type="number" className="pm-shift-n" aria-label="Working days" min={1} max={366} value={by}
            onChange={(e) => setBy(e.target.value)} onBlur={() => setBy(String(shiftBy))} />
          <span>working days</span>
          <select className="pm-shift-way" aria-label="Direction" value={way} onChange={(e) => setWay(e.target.value === 'earlier' ? 'earlier' : 'later')}>
            <option value="later">later</option>
            <option value="earlier">earlier</option>
          </select>
          <Button size="sm" disabled={shift.isPending || shiftRefs.length === 0} onClick={shiftPicked}>Shift</Button>
          <span className="pm-tsep" aria-hidden="true" />
          <label className="pm-force" title="Push past Not yet plan, steps out of order and the master schedule (a Task and a PIC are still needed)">
            <input type="checkbox" checked={force} onChange={(e) => setForce(e.target.checked)} />Force
          </label>
          <PushFlow epic={epic} v={v} name={name} body={{ refs: shiftRefs, force }} label="Push" icon="upload" size="sm"
            title="Push the picked functions and steps to Jira" onDone={() => setPicked(new Set())} />
          <Button size="sm" appearance="subtle" onClick={() => setPicked(new Set())}>Cancel</Button>
        </div>
      )}
      <div className="pm-scroll" ref={scroller}>
        <table className="pm-table">
          <thead>
            <tr className="pm-head">
              <th scope="col" className="pm-c0">Step · sub-task</th>
              <th scope="col" className="pm-c1">PIC</th>
              <th scope="col" className="pm-c2">Plan Estimation</th>
              <th scope="col" className="pm-c3">Planned</th>
              <th scope="col" className="pm-c4">Not yet plan</th>
              <th scope="col" className="pm-c5">Start</th>
              <th scope="col" className="pm-c6">End</th>
              <th scope="col" className="pm-c7" title="Remaining Estimate in Jira now (Forecast estimation), for reference: the plan does not use it">Actual remaining</th>
              {days.map((d) => (
                <th key={d} scope="col" aria-label={d} className={`pm-day${d === today ? ' pm-today' : ''}`}>
                  <span className="pm-wd">{d === today ? 'Today' : weekday(d)}</span><span className="pm-dn">{dm(d)}</span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {phases.length === 0 && (
              <tr><td colSpan={8 + days.length} className="empty-row" style={{ fontSize: 13 }}>Nothing to plan yet: add a function or a task.</td></tr>
            )}
            {phases.length > 0 && filtering && matched === 0 && (
              <tr><td colSpan={8 + days.length} className="empty-row" style={{ fontSize: 13 }}>No step matches the filters.</td></tr>
            )}
            {body}
          </tbody>
          {footLines.length > 0 && (
            <tfoot>
              <tr className="pm-foot">
                <th scope="row" className="pm-c0">Total per day</th>
                <td className="pm-c1" />
                <td className="pm-c2">{sumText(footLines.reduce((a, l) => a + l.plan_estimate_h, 0))}</td>
                <td className="pm-c3">{sumText(footLines.reduce((a, l) => a + l.plan_h, 0))}</td>
                <td className="pm-c4">{sumText(footLines.filter((l) => !l.closed).reduce((a, l) => a + l.plan_not_yet_h, 0))}</td>
                <td className="pm-c5" /><td className="pm-c6" /><td className="pm-c7" />
                {days.map((d) => {
                  const t = footLines.reduce((a, l) => a + sum(l.cells, d), 0);
                  return <td key={d} className={`pm-cell${d === today ? ' pm-today' : ''}${t < 0.005 ? ' zero' : ''}`}>{sumText(t)}</td>;
                })}
              </tr>
            </tfoot>
          )}
        </table>
      </div>
    </section>
  );
}
