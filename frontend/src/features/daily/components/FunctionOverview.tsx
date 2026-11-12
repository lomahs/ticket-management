import { Link } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { useMemberMap } from '../../../shared/api/queries';
import type { Member, Schemas } from '../../../shared/api/types';
import { Icon } from '../../../shared/ds';
import { dm } from '../../../shared/format/dates';
import { STEPS, statusOf } from '../../../shared/format/labels';
import { useOpenTicket } from '../../../shared/state/ticket';
import { C, CountChip, Initial, SchedPill, StopDot, type Sched, type StopKind } from './metro';

type Group = Schemas['DailyGroup'];
type Task = Schemas['DailySubPhase'];
type Fn = Schemas['DailyFunction'];
type Ticket = Schemas['DailyTicket'];
type Step = Schemas['DailyStep'];

/** Name · the step columns · Schedule. */
const COLS = '240px minmax(0,1fr) 128px';
const JM_STEPS = new Set(['jmr', 'fixj']);
const stepLabel = (k: string) => STEPS.find((s) => s.k === k)?.short ?? k;
/** "10/01 – 10/02": a stop's Planned Start – Planned End. */
const plan = (x: { planned_start?: string | null; planned_end?: string | null }) =>
  (x.planned_start || x.planned_end ? `${dm(x.planned_start) || '?'} – ${dm(x.planned_end) || '?'}` : '');

/**
 * The functions and tickets of the phase picked in Process (or of every phase), one card per Task. A Task opens by itself
 * when something in it is late or in progress, and stays closed when it is done or has not started; Expand all / Collapse
 * all override that. Functions run on 5 step columns: a 3-step flow ends on Fix BAL, the JM columns greyed out. `today` is the
 * day shown (live or captured): a stop whose Planned End falls on it is due.
 */
export function FunctionOverview({ groups, phase, none, today }: { groups: Group[]; phase: string | undefined; none: string | null; today: string }) {
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const picked = phase != null ? groups.filter((g) => g.name === phase) : [];
  const shown = picked.length ? picked : groups;
  const tasks = shown.flatMap((g) => g.sub_phases);
  const setAll = (v: boolean) => setOpen(Object.fromEntries(tasks.map((t) => [t.key, v])));
  return (
    <section aria-label="Function overview" style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 6, minWidth: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <h2 style={{ margin: 0, font: '700 22px/1.2 var(--font-display)', color: 'var(--text-heading)' }}>Function overview</h2>
        <span className="spacer" />
        <Legend />
        <span aria-hidden="true" style={{ width: 1, height: 22, background: 'var(--border-subtle)' }} />
        <span style={{ display: 'inline-flex', gap: 2 }}>
          <button type="button" className="do-mini" onClick={() => setAll(true)}><Icon name="chevrons-up-down" size={14} />Expand all</button>
          <button type="button" className="do-mini" onClick={() => setAll(false)}><Icon name="chevrons-down-up" size={14} />Collapse all</button>
        </span>
      </div>
      {none ? (
        <div className="do-acc" style={{ padding: '22px 18px', textAlign: 'center', fontSize: 13, color: 'var(--text-muted)' }}>{none}</div>
      ) : (
        <div style={{ overflowX: 'auto', margin: '0 -4px', padding: '0 4px 6px' }}>
          <div style={{ minWidth: 980, display: 'flex', flexDirection: 'column', gap: 12 }}>
            {shown.map((g) => (
              <div key={g.name} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                {shown.length > 1 && (
                  <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, padding: '10px 2px 0' }}>
                    <b style={{ font: '800 14px/1 var(--font-text)', color: 'var(--text-heading)' }}>{g.name}</b>
                    <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{g.state === 'not_started' ? 'not started' : `${g.pct}% · ${g.closed}/${g.total} done`}</span>
                  </div>
                )}
                <PhaseBody g={g} today={today} open={open} toggle={(k, v) => setOpen((o) => ({ ...o, [k]: v }))} />
              </div>
            ))}
          </div>
        </div>
      )}
    </section>
  );
}

function Legend() {
  const item = (dot: ReactNode, label: string) => <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}>{dot}{label}</span>;
  const ring = (col: string) => <span style={{ width: 14, height: 14, borderRadius: '50%', boxShadow: `inset 0 0 0 2px ${col}` }} />;
  const fill = (col: string) => <span style={{ width: 14, height: 14, borderRadius: '50%', background: col }} />;
  return (
    <span style={{ display: 'flex', gap: 12, flexWrap: 'wrap', fontSize: 11.5, color: 'var(--text-muted)' }}>
      {item(<span className="do-dot" style={{ width: 14, height: 14, background: C.done }}><Icon name="check" size={9} strokeWidth={3.2} /></span>, 'Done')}
      {item(fill(C.wip), 'Now')}
      {item(fill(C.due), 'Due today')}
      {item(fill(C.late), 'Late')}
      {item(ring(C.jm), 'JM step')}
    </span>
  );
}

function PhaseBody({ g, today, open, toggle }: { g: Group; today: string; open: Record<string, boolean>; toggle: (key: string, v: boolean) => void }) {
  if (g.state === 'not_started' && !g.sub_phases.length) {
    return (
      <div className="do-acc" style={{ padding: '22px 18px', textAlign: 'center', fontSize: 13, color: 'var(--text-muted)' }}>
        <b style={{ color: 'var(--text-heading)' }}>{g.name}</b> has not started: its Tasks and functions show here once they are planned.
      </div>
    );
  }
  if (!g.sub_phases.length) {
    // a group captured before Tasks were rolled up (an older snapshot): one summary card
    const s: Sched = g.state === 'done' ? 'done' : g.late ? 'late' : 'ok';
    return (
      <div className="do-acc" style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '14px 18px' }}>
        <b style={{ font: '700 15px/1.2 var(--font-text)', color: 'var(--text-heading)' }}>{g.name}</b>
        <span style={{ fontSize: 12.5, color: 'var(--text-muted)' }}>{g.closed}/{g.total} done</span>
        <span className="spacer" />
        <SchedPill s={s} late={0} />
      </div>
    );
  }
  return <>{g.sub_phases.map((t) => <TaskCard key={t.key} t={t} today={today} open={open[t.key]} toggle={(v) => toggle(t.key, v)} />)}</>;
}

/** Where a row stands: done, late (the most days late), not started, else on track. */
function schedOf(items: { status: string; late: boolean; late_days: number }[]): [Sched, number] {
  if (items.every((s) => s.status === 'C')) return ['done', 0];
  const late = Math.max(0, ...items.filter((s) => s.status !== 'C' && s.late).map((s) => s.late_days));
  if (late) return ['late', late];
  if (items.every((s) => s.status === 'N')) return ['nys', 0];
  return ['ok', 0];
}

const fnSteps = (f: Fn) => f.steps.filter((s): s is Step => s != null);

/** A Task's card: a header that keeps its summary when collapsed, and its rows when open. */
function TaskCard({ t, today, open, toggle }: { t: Task; today: string; open: boolean | undefined; toggle: (v: boolean) => void }) {
  const rows = t.functions.length ? t.functions.map((f) => schedOf(fnSteps(f))[0]) : t.tickets.map((x) => schedOf([x])[0]);
  const late = rows.filter((s) => s === 'late').length || (rows.length ? 0 : t.late);
  const running = rows.filter((s) => s === 'ok').length || (!rows.length && t.pct > 0 && t.pct < 100 ? 1 : 0);
  const done = t.total > 0 && t.closed >= t.total;
  const expanded = open ?? ((late > 0 || running > 0) && !done);
  return (
    <div className="do-acc">
      <button type="button" className="do-acc-h" aria-expanded={expanded} onClick={() => toggle(!expanded)}>
        <Icon name="chevron-right" size={16} style={{ color: 'var(--text-muted)', transition: 'transform var(--duration-fast) var(--easing-standard)', transform: expanded ? 'rotate(90deg)' : 'none' }} />
        <b style={{ font: '700 15px/1.2 var(--font-text)', color: 'var(--text-heading)' }}>{t.name}</b>
        <span style={{ font: '500 12px var(--font-mono)', color: 'var(--text-muted)' }}>{t.key}</span>
        <span aria-hidden="true" style={{ width: 110, height: 6, borderRadius: 99, background: 'var(--border-subtle)', overflow: 'hidden', marginLeft: 6 }}>
          <span style={{ display: 'block', height: 6, width: `${t.pct}%`, background: 'var(--text-heading)', borderRadius: 99 }} />
        </span>
        <span style={{ font: '700 13px/1 var(--font-text)', color: 'var(--text-heading)' }}>{t.pct}%</span>
        <span style={{ fontSize: 12.5, color: 'var(--text-muted)' }}>{t.closed}/{t.total} done</span>
        <span className="spacer" />
        {late > 0 && <CountChip tone="late">{late} late</CountChip>}
        {running > 0 && <CountChip tone="run">{running} in progress</CountChip>}
        {done && <SchedPill s="done" />}
      </button>
      {expanded && (
        <div className="do-acc-b">
          {t.functions.length > 0 && <Functions fns={t.functions} today={today} />}
          {t.tickets.length > 0 && <Tickets tickets={t.tickets} today={today} />}
          <Unmatched t={t} />
          {!t.functions.length && !t.tickets.length && !t.unmatched && (
            <div style={{ padding: '12px 18px', fontSize: 12.5, color: 'var(--text-muted)' }}>No functions or tickets listed.</div>
          )}
        </div>
      )}
    </div>
  );
}

/** The Task's sub-tasks whose title does not follow the name rule: a warning with a link to Ticket Check. */
function Unmatched({ t }: { t: Task }) {
  if (t.unmatched <= 0) return null;
  return (
    <div role="note" className="do-callout">
      <span style={{ display: 'flex', color: 'var(--do-warn-fg)' }}><Icon name="triangle-alert" size={16} /></span>
      <span><b style={{ color: 'var(--text-heading)' }}>{t.unmatched} sub-task{t.unmatched === 1 ? '' : 's'}</b> of {t.name} do not follow the name rule, so they are not listed here.</span>
      <span className="spacer" />
      <Link to="/ticket-check" search={{ check: 'rule' }} style={{ fontWeight: 700, whiteSpace: 'nowrap' }}>Review in Ticket Check →</Link>
    </div>
  );
}

const firstStart = (f: Fn) => fnSteps(f)[0]?.planned_start ?? '9999';

function Functions({ fns, today }: { fns: Fn[]; today: string }) {
  const short = fns.every((f) => f.steps.length < STEPS.length);
  const rows = [...fns].sort((a, b) => firstStart(a).localeCompare(firstStart(b)) || a.name.localeCompare(b.name));
  return (
    <>
      <div className="do-head" style={{ gridTemplateColumns: COLS }}>
        <span>Function <span style={{ fontWeight: 500, color: 'var(--text-muted)' }}>· sorted by Create start</span></span>
        <span style={{ display: 'grid', gridTemplateColumns: `repeat(${STEPS.length},1fr)`, textAlign: 'center' }}>
          {STEPS.map((s, i) => (short && i >= 3 ? (
            <span key={s.k} style={{ color: 'var(--text-disabled)', background: 'var(--do-zone)', margin: '-10px 0 -8px', padding: '10px 0 8px' }}>
              {s.short} <span className="do-tag" style={{ marginLeft: 4 }}>N/A</span>
            </span>
          ) : <span key={s.k} style={JM_STEPS.has(s.k) ? { color: C.jm } : undefined}>{s.short}</span>))}
        </span>
        <span>Schedule</span>
      </div>
      <div className="do-rows">{rows.map((f) => <FunctionLine key={f.id} f={f} today={today} />)}</div>
    </>
  );
}

/**
 * Where a function's JM round is while its JM Review is under way (WIP or Resolved): the JM Review's assignee tells. A JM:
 * waiting for the JM review; a team member: the team is fixing JM's comments. The index of that step, or -1.
 */
function jmRoundAt(steps: Fn['steps']): number {
  const at = steps.findIndex((c) => c?.step === 'jmr');
  const review = steps[at];
  if (!review || (review.status !== 'W' && review.status !== 'R')) return -1;
  if (review.with_jm) return at;
  return review.assignee_id ? steps.findIndex((c) => c?.step === 'fixj') : -1;
}

function NameCell({ who, title, sub, m }: { who: string | null | undefined; title: string; sub: ReactNode; m: Member | undefined }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
      <Initial m={m} id={who} />
      <div style={{ minWidth: 0 }}>
        <div className="ellipsis" style={{ font: '700 14px/1.35 var(--font-text)', color: 'var(--text-heading)' }} title={title}>{title}</div>
        <div style={{ font: '500 12.5px/1.4 var(--font-text)', color: 'var(--text-body)', marginTop: 2 }}>{sub}</div>
      </div>
    </div>
  );
}

/** One function on the 5 step columns: done stops, the step it is on now (with its holder), the stops ahead; its schedule. */
function FunctionLine({ f, today }: { f: Fn; today: string }) {
  const members = useMemberMap();
  const open = useOpenTicket();
  const n = STEPS.length;
  const have = f.steps.length;
  const cur = f.steps.findIndex((s) => s != null && s.status !== 'C');
  const round = jmRoundAt(f.steps);
  const [sched, late] = schedOf(fnSteps(f));
  const now = cur >= 0 ? f.steps[cur] : null;
  const segs: ReactNode[] = [];
  for (let i = 0; i < have - 1; i++) {
    const s = f.steps[i];
    const next = f.steps[i + 1];
    const bg = s?.status === 'C' && next?.status === 'C' ? C.done
      : s?.status === 'C' && i + 1 === cur ? (next?.late ? C.late : C.done) : C.line;
    segs.push(<span key={`s${i}`} className="do-seg" style={{ left: `${((i + 0.5) / n) * 100}%`, width: `${100 / n}%`, background: bg }} />);
  }
  if (have < n) {
    segs.push(<span key="zone" className="do-zone" style={{ left: `${(have / n) * 100}%` }} />);
    segs.push(<span key="flag" className="do-flag" title="Last step of this flow" style={{ left: `calc(${((have - 0.5) / n) * 100}% + 16px)` }}><Icon name="flag" size={14} /></span>);
  }
  return (
    <div className={'do-row' + (sched === 'late' ? ' late' : '')} style={{ gridTemplateColumns: COLS }}>
      <NameCell who={f.ba_id} m={members[f.ba_id ?? '']} title={f.name}
        sub={now ? <><span style={{ color: 'var(--text-muted)' }}>Now</span> <b style={{ fontWeight: 600 }}>{stepLabel(now.step)}</b> · {members[now.assignee_id ?? '']?.name ?? 'unassigned'}</> : 'All steps done'} />
      <div className="do-track" style={{ gridTemplateColumns: `repeat(${n},1fr)` }}>
        {segs}
        {Array.from({ length: n }, (_, i) => {
          const s = f.steps[i];
          if (!s) return <span key={i} />;
          return <StepStop key={i} s={s} today={today} now={i === cur} round={i === round} m={members[s.assignee_id ?? '']} open={() => open(s.key)} />;
        })}
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 5, alignItems: 'flex-start' }}>
        <SchedPill s={sched} late={late} />
        <span style={{ font: '500 12px/1.3 var(--font-mono)', color: 'var(--text-body)' }}>finish {dm(fnSteps(f).at(-1)?.planned_end) || '–'}</span>
      </div>
    </div>
  );
}

/**
 * A step's stop; it opens the sub-task. Only a started step shows its holder and the highlight (NYS stays a ring, red when
 * late); a step due today that is not that is a small yellow dot. Its tooltip says the step, status, holder, plan, rounds and where the JM round is.
 */
function StepStop({ s, today, now, round, m, open }: { s: Step; today: string; now: boolean; round: boolean; m: Member | undefined; open: () => void }) {
  const jm = JM_STEPS.has(s.step);
  const late = s.late && s.status !== 'C' ? s.late_days : 0;
  const due = s.status !== 'C' && s.planned_end === today;
  const started = s.status !== 'N';
  const col = late ? C.late : due ? C.due : jm ? C.jm : C.wip;
  const kind: StopKind = s.status === 'C' ? 'done' : now && started ? 'now' : due ? 'due' : started ? 'open' : 'todo';
  const st = statusOf(s.status);
  const where = s.with_jm ? 'JM' : 'Team';
  const tip = `${s.key} · ${STEPS.find((x) => x.k === s.step)?.label ?? s.step} · ${st.label}${late ? ` · ${late} working days late` : ''}${due ? ' · due today' : ''} · ${m?.name ?? 'unassigned'}`
    + ` · plan ${dm(s.planned_start) || '?'} – ${dm(s.planned_end) || '?'}${s.rounds ? ` · round ${s.rounds}, with ${where}` : ''}`
    + (round ? ` · now: ${s.step === 'jmr' ? 'waiting for the JM review' : 'fixing JM comments'}` : '');
  return (
    <button type="button" className="do-st" title={tip} aria-label={`${stepLabel(s.step)} · ${s.status === 'C' ? 'Done' : st.label}${late ? ` · ${late}d late` : ''}${due ? ' · due today' : ''}`} onClick={open}>
      <span className="do-node"><StopDot kind={kind} col={kind === 'todo' ? (late ? C.late : jm ? C.jm : C.lineStrong) : col} initial={(m?.name ?? '?').slice(0, 1)} /></span>
      <span className="do-dl">{plan(s)}</span>
      {late > 0 && <span style={{ font: '800 10.5px/1 var(--font-text)', color: C.late }}>+{late}d</span>}
      {!late && s.rounds > 1 && <span style={{ font: '700 10.5px/1 var(--font-text)', color: 'var(--text-muted)' }}>↻{s.rounds}</span>}
    </button>
  );
}

/** Single tickets: one stop each, with their plan. */
function Tickets({ tickets, today }: { tickets: Ticket[]; today: string }) {
  const rows = [...tickets].sort((a, b) => (a.planned_start ?? '9999').localeCompare(b.planned_start ?? '9999') || a.key.localeCompare(b.key));
  return (
    <>
      <div className="do-head" style={{ gridTemplateColumns: COLS }}>
        <span>Ticket <span style={{ fontWeight: 500, color: 'var(--text-muted)' }}>· sorted by plan start</span></span>
        <span style={{ textAlign: 'center' }}>Plan</span>
        <span>Schedule</span>
      </div>
      <div className="do-rows">{rows.map((x) => <TicketLine key={x.key} x={x} today={today} />)}</div>
    </>
  );
}

function TicketLine({ x, today }: { x: Ticket; today: string }) {
  const members = useMemberMap();
  const open = useOpenTicket();
  const m = members[x.assignee_id ?? ''];
  const [sched, late] = schedOf([x]);
  const st = statusOf(x.status);
  const due = x.status !== 'C' && x.planned_end === today;
  const col = late ? C.late : due ? C.due : C.wip;
  const kind: StopKind = x.status === 'C' ? 'done' : x.status !== 'N' ? 'now' : due ? 'due' : 'todo';
  return (
    <div className={'do-row' + (sched === 'late' ? ' late' : '')} style={{ gridTemplateColumns: COLS }}>
      <NameCell who={x.assignee_id} m={m} title={`${x.name} · ${x.key}`}
        sub={x.status === 'C' ? 'Done' : <><span style={{ color: 'var(--text-muted)' }}>Now</span> <b style={{ fontWeight: 600 }}>{st.label}</b> · {m?.name ?? 'unassigned'}</>} />
      <div className="do-track">
        <button type="button" className="do-st" onClick={() => open(x.key)}
          title={`${x.key} · ${st.label}${late ? ` · ${late} working days late` : ''}${due ? ' · due today' : ''} · plan ${dm(x.planned_start) || '?'} – ${dm(x.planned_end) || '?'}`}
          aria-label={`${x.status === 'C' ? 'Done' : st.label}${late ? ` · ${late}d late` : ''}${due ? ' · due today' : ''}`}>
          <span className="do-node"><StopDot kind={kind} col={kind === 'todo' ? (late ? C.late : C.lineStrong) : col} initial={(m?.name ?? '?').slice(0, 1)} /></span>
          <span className="do-dl">{plan(x)}</span>
          {late > 0 && <span style={{ font: '800 10.5px/1 var(--font-text)', color: C.late }}>+{late}d</span>}
        </button>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 5, alignItems: 'flex-start' }}>
        <SchedPill s={sched} late={late} />
        <span style={{ font: '500 12px/1.3 var(--font-mono)', color: 'var(--text-body)' }}>finish {dm(x.planned_end) || '–'}</span>
      </div>
    </div>
  );
}
