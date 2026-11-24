import { useQuery } from '@tanstack/react-query';
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useMeta } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button, DateField, Icon, type IconName } from '../../../shared/ds';
import { dm, f2, hrs, WDN } from '../../../shared/format/dates';
import { STEPS } from '../../../shared/format/labels';
import { useOpenTicket } from '../../../shared/state/ticket';
import { useToast } from '../../../shared/state/toast';
import { detailPlanQuery, useCellWriter, useEditJiraLine, useEditStep, useDeleteStep, useShiftPlan, type DetailPlanView, type MatrixLine } from './api';
import { hoursByDay, lineMarks, withTyped } from './matrixModel';
import { usePersonName, type Person } from './DetailPlan';
import { checkText } from './PlanSummary';
import { PushFlow } from './PushControls';
import { left, LineActionsContext, Mark, slotOf, step, StepLine, type LineActions, type Slot } from './StepLine';

type Row = Schemas['EstimationRow'];
export type { Slot };

/** A sum on a folded row: "–" for none. */
const sumText = (n: number) => (Math.abs(n) < 0.005 ? '–' : f2(n));
const weekday = (d: string) => WDN[new Date(`${d}T00:00:00Z`).getUTCDay()];
const plural = (n: number, w: string) => `${n} ${w}${n === 1 ? '' : 's'}`;

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
export function PlanMatrix({ epic, v: plan, people, admin, assign, swap }: {
  epic: string; v: DetailPlanView; people: Person[]; admin: boolean;
  assign: (r: Row, slot: Slot, id: string | null) => void; swap: (a: number, b: number) => void;
}) {
  const toast = useToast();
  const openTicket = useOpenTicket();
  const today = useMeta().data?.today ?? '';
  const edit = useEditStep(epic);
  const cells = useCellWriter(epic);
  // the hours typed show at once, before the plan that follows comes back
  const v = withTyped(plan, cells.typed);
  const jira = useEditJiraLine(epic);
  const shift = useShiftPlan(epic);
  const del = useDeleteStep(epic);
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
  const sendCell = (l: MatrixLine, day: string, hours: number) => cells.send({ ref: l.ref, day, hours }).catch(fail);
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
  const rowOf = (l: MatrixLine) => open.find((r) => r.id === l.row_id);
  const picOf = (l: MatrixLine) => { const s = slotOf(l); const r = rowOf(l); return (s && r ? r[s] : l.pic_id) ?? ''; };

  // what is wrong where: the problems on their row (the lines' own come with each line), the warnings on the phase
  const rowIssues = (r: Row) => [...new Set(v.issues.filter((i) => i.row_id === r.id && i.field === 'task').map((i) => i.message))];
  const phaseWarnings = (p: string) => v.over_budget.filter((o) => o.phase === p)
    .map((o) => `${name(o.member_id)} is over budget in ${o.phase}: ${hrs(o.planned_h)} of ${hrs(o.budget_h)}`);

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
  // kept as it is while the window and the days asked do not change: every line gets it
  const days = useMemo(() => plan.window.filter((d) => (!from || d >= from) && (!to || d <= to)), [plan.window, from, to]);
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

  const deleteStep = (l: MatrixLine) => { if (l.row_id != null) del.mutateAsync({ rowId: l.row_id, step: l.step }).catch(fail); };

  // what the step lines ask of the matrix: one object for its whole life, calling the handlers of the last render, so a line
  // renders again only when its own data changes
  const latest = useRef<LineActions | null>(null);
  useLayoutEffect(() => {
    latest.current = { sendCell, sendStep, sendJira, deleteStep, assign, togglePick, openTicket };
  });
  const [actions] = useState<LineActions>(() => ({
    sendCell: (l, day, hours) => latest.current?.sendCell(l, day, hours) ?? Promise.resolve(),
    sendStep: (l, p) => latest.current?.sendStep(l, p) ?? Promise.resolve(),
    sendJira: (key, total, pic) => latest.current?.sendJira(key, total, pic) ?? Promise.resolve(),
    deleteStep: (l) => latest.current?.deleteStep(l),
    assign: (r, slot, id) => latest.current?.assign(r, slot, id),
    togglePick: (key) => latest.current?.togglePick(key),
    openTicket: (key) => latest.current?.openTicket(key),
  }));
  // the marks of each line, kept as they were while they do not change (a select is shared structurally)
  const marks = useQuery({ ...detailPlanQuery(epic), select: lineMarks }).data;


  // each line's hours per day, once per render, for the folded levels and the total per day
  const perDay = new Map(v.lines.map((l) => [l.ref, hoursByDay(l.cells)]));
  const byDay = (l: MatrixLine) => perDay.get(l.ref) ?? new Map<string, number>();

  /** The day cells of a folded level: its steps' hours per day; empty while it is open. */
  const sums = (lines: MatrixLine[], closed: boolean) => days.map((d) => {
    const t = closed ? lines.filter((l) => !l.removed).reduce((a, l) => a + (byDay(l).get(d) ?? 0), 0) : 0;
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
            {r && <Mark level="error" msgs={rowIssues(r)} />}
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

  const line = (l: MatrixLine) => (
    <StepLine key={l.ref} l={l} row={rowOf(l)} hand={v.by_hand.find((y) => y.row_id === l.row_id)} marks={marks?.[l.ref]} days={days} today={today}
      admin={admin} picked={picked.has(l.ref)} people={people} deleting={del.isPending} />
  );

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
          <span className="pm-ph">{foldButton(`phase:${p}`, `the functions of ${p}`)}<span className="pm-ph-name">{p}</span><span className="pm-badge">{plural(fns, 'function')}</span><Mark level="warning" msgs={phaseWarnings(p)} /></span>
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
    <LineActionsContext value={actions}>
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
                    const t = footLines.reduce((a, l) => a + (byDay(l).get(d) ?? 0), 0);
                    return <td key={d} className={`pm-cell${d === today ? ' pm-today' : ''}${t < 0.005 ? ' zero' : ''}`}>{sumText(t)}</td>;
                  })}
                </tr>
              </tfoot>
            )}
          </table>
        </div>
      </section>
    </LineActionsContext>
  );
}
