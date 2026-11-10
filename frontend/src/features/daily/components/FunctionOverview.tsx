import { Link } from '@tanstack/react-router';
import { useState, type CSSProperties } from 'react';
import { useMemberMap } from '../../../shared/api/queries';
import type { Schemas, Status } from '../../../shared/api/types';
import { Icon, type IconName } from '../../../shared/ds';
import { dm } from '../../../shared/format/dates';
import { STEPS, statusOf } from '../../../shared/format/labels';
import { useOpenTicket } from '../../../shared/state/ticket';
import { Avatar, av } from '../../../shared/ui/bits';
import { ACTIVE_RING, chip, Legend, lateChip } from './chips';

type Group = Schemas['DailyGroup'];
type Task = Schemas['DailySubPhase'];
type Fn = Schemas['DailyFunction'];
type Ticket = Schemas['DailyTicket'];
type Step = Schemas['DailyStep'];

/** Function · BA · one column per step of the tab's longest flow (3 or 5). */
const fnCols = (n: number) => `minmax(220px,2fr) 160px repeat(${n},minmax(96px,1fr))`;
const ONE_COLS = 'minmax(240px,2fr) 160px 150px 110px';
const PAD = '0 20px';

type Dir = 'asc' | 'desc';
interface Sort<K extends string> { key: K; dir: Dir }
type FnKey = 'name' | 'owner';
type OneKey = 'name' | 'owner' | 'status' | 'end';
const STATUS_ORDER: Record<string, number> = { N: 0, W: 1, R: 2, C: 3 };

/** Asc, then desc, then the server's order again. */
function next<K extends string>(s: Sort<K> | null, key: K): Sort<K> | null {
  if (s?.key !== key) return { key, dir: 'asc' };
  return s.dir === 'asc' ? { key, dir: 'desc' } : null;
}

function sorted<T, K extends string>(items: T[], s: Sort<K> | null, value: (t: T, k: K) => string | number) {
  if (!s) return items;
  const sign = s.dir === 'asc' ? 1 : -1;
  return [...items].sort((a, b) => {
    const x = value(a, s.key);
    const y = value(b, s.key);
    return (x < y ? -1 : x > y ? 1 : 0) * sign;
  });
}

/**
 * The anken's Daily phases, one tab each (none ticked: the phase groups that have started), in the server's order; the tab
 * in the URL, else the first in progress, else the first. A tab lists its Tasks: the functions as a matrix of their steps,
 * then the single tickets. A past day (`live` false) has no group for a Daily phase that had not started then.
 */
export function FunctionOverview({ groups, daily, live, tab, pick }: {
  groups: Group[]; daily: string[]; live: boolean; tab: string | undefined; pick: (tab: string) => void;
}) {
  const [fnSort, setFnSort] = useState<Sort<FnKey> | null>(null);
  const [oneSort, setOneSort] = useState<Sort<OneKey> | null>(null);
  const shown = daily.length ? groups.filter((g) => daily.includes(g.name)) : groups.filter((g) => g.state !== 'not_started');
  const active = shown.find((g) => g.name === tab) ?? shown.find((g) => g.state === 'current') ?? shown[0];
  const none = !daily.length ? 'No phase has started yet.'
    : live ? 'No phase to show. Pick the phases in Settings → Structure.' : 'None of the Daily phases had started on this day.';
  return (
    <section className="card" aria-label="Function overview" style={{ overflow: 'hidden', borderRadius: 14, padding: 0 }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12, padding: '16px 20px', borderBottom: '1px solid var(--border-subtle)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <span style={{ font: '700 18px/1.2 var(--font-display)', color: 'var(--text-heading)' }}>Function overview</span>
          {active && (
            <span style={{ fontSize: 14, color: 'var(--text-muted)' }}>
              {active.pct}% · {active.closed}/{active.total} done{active.late > 0 && <b style={{ color: 'var(--hl-red-fg)' }}> · {active.late} late</b>}
            </span>
          )}
          <span className="spacer" />
          <Legend />
        </div>
        {shown.length > 0 && (
          <div className="seg" role="tablist" aria-label="Phases" style={{ alignSelf: 'flex-start', flexWrap: 'wrap', borderRadius: 10 }}>
            {shown.map((g) => {
              const on = g === active;
              return (
                <button key={g.name} type="button" role="tab" aria-selected={on} onClick={() => pick(g.name)} style={{
                  height: 30, padding: '0 14px', borderRadius: 8, font: '600 13px/1 var(--font-text)',
                  background: on ? 'var(--button-primary-bg)' : 'transparent', color: on ? 'var(--button-primary-fg)' : 'var(--text-heading)',
                }}>
                  {g.name} <span style={{ fontWeight: 500, opacity: 0.7 }}>{g.pct}%</span>
                </button>
              );
            })}
          </div>
        )}
      </div>
      {!active ? (
        <div className="empty-row" style={{ borderTop: 0 }}>
          {none}
        </div>
      ) : (
        <div role="tabpanel" aria-label={active.name} style={{ overflowX: 'auto' }}>
          <div style={{ minWidth: 900 }}>
            {!active.sub_phases.length ? <EmptyGroup g={active} />
              : <TabBody g={active} fnSort={fnSort} setFnSort={setFnSort} oneSort={oneSort} setOneSort={setOneSort} />}
          </div>
        </div>
      )}
    </section>
  );
}

/** The matrix's step count: the longest flow among the group's functions (3 or 5). */
const stepsOf = (g: Group) => Math.max(0, ...g.sub_phases.flatMap((t) => t.functions.map((f) => f.steps.length)));

/** A group captured before Tasks were rolled up (an older snapshot keeps a done group without its Tasks): one row. */
function EmptyGroup({ g }: { g: Group }) {
  const badge = g.state === 'not_started' ? { s: 'N' as const, label: 'Not started' } : g.state === 'done' ? { s: 'C' as const, label: 'Done' } : null;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, minHeight: 40, padding: PAD, borderBottom: '1px solid var(--border-subtle)' }}>
      <span style={nameStyle}>{g.name}</span>
      {badge && <span style={{ ...chip(badge.s), padding: '2px 10px', borderRadius: 6, font: '700 12px/1.3 var(--font-text)' }}>{badge.label}</span>}
    </div>
  );
}

const head: CSSProperties = { display: 'grid', gap: '0 10px', alignItems: 'center', padding: '8px 20px 6px', borderBottom: '1px solid var(--border-subtle)' };

/**
 * A tab: the matrix (one column header, then each Task with functions as a heading row and its functions), then the single
 * tickets the same way. A Task with both kinds is headed in each part; its note on unlisted sub-tasks goes under its last part.
 */
function TabBody({ g, fnSort, setFnSort, oneSort, setOneSort }: {
  g: Group;
  fnSort: Sort<FnKey> | null; setFnSort: (s: Sort<FnKey> | null) => void; oneSort: Sort<OneKey> | null; setOneSort: (s: Sort<OneKey> | null) => void;
}) {
  const members = useMemberMap();
  const name = (id?: string | null) => (members[id ?? '']?.name ?? id ?? '').toLowerCase();
  const n = stepsOf(g);
  const fnTasks = g.sub_phases.filter((t) => t.functions.length > 0);
  const oneTasks = g.sub_phases.filter((t) => t.tickets.length > 0);
  const bare = g.sub_phases.filter((t) => !t.functions.length && !t.tickets.length);
  return (
    <>
      {fnTasks.length > 0 && (
        <>
          <div style={{ ...head, gridTemplateColumns: fnCols(n) }}>
            <Head label="Function" on={fnSort?.key === 'name' ? fnSort.dir : null} click={() => setFnSort(next(fnSort, 'name'))} />
            <Head label="BA" on={fnSort?.key === 'owner' ? fnSort.dir : null} click={() => setFnSort(next(fnSort, 'owner'))} />
            {STEPS.slice(0, n).map((st) => <span key={st.k} style={{ textAlign: 'center', font: '600 12px/1.2 var(--font-text)', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>{st.short}</span>)}
          </div>
          {fnTasks.map((t) => (
            <div key={t.key}>
              <TaskHead t={t} />
              {sorted(t.functions, fnSort, (f: Fn, k) => (k === 'name' ? f.name.toLowerCase() : name(f.ba_id))).map((f) => <FunctionRow key={f.id} f={f} n={n} />)}
              {!t.tickets.length && <Unmatched t={t} />}
            </div>
          ))}
        </>
      )}
      {oneTasks.length > 0 && (
        <>
          <div style={{ ...head, gridTemplateColumns: ONE_COLS }}>
            {([['name', 'Ticket'], ['owner', 'Assignee'], ['status', 'Status'], ['end', 'Planned end']] as [OneKey, string][]).map(([k, l]) => (
              <Head key={k} label={l} on={oneSort?.key === k ? oneSort.dir : null} click={() => setOneSort(next(oneSort, k))} center={k === 'status'} />
            ))}
          </div>
          {oneTasks.map((t) => (
            <div key={t.key}>
              <TaskHead t={t} />
              {sorted(t.tickets, oneSort, (x: Ticket, k) => (
                k === 'name' ? x.name.toLowerCase() : k === 'owner' ? name(x.assignee_id) : k === 'status' ? STATUS_ORDER[x.status] ?? 0
                  : x.planned_end ?? '9999')).map((x) => <TicketRow key={x.key} t={x} />)}
              <Unmatched t={t} />
            </div>
          ))}
        </>
      )}
      {bare.map((t) => <div key={t.key}><TaskHead t={t} /><Unmatched t={t} /></div>)}
    </>
  );
}

/** A Task's heading row: its name and done / total. */
function TaskHead({ t }: { t: Task }) {
  return (
    <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, padding: '8px 20px', background: 'var(--surface-hover)', borderBottom: '1px solid var(--border-subtle)' }}>
      <span className="ellipsis" style={{ font: '700 14px/1.3 var(--font-text)', color: 'var(--text-heading)' }} title={t.key}>{t.name}</span>
      <span style={{ fontSize: 13, color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>{t.closed} / {t.total} done</span>
    </div>
  );
}

/** The Task's sub-tasks whose title does not follow the name rule: a count and a link to Ticket Check. */
function Unmatched({ t }: { t: Task }) {
  if (t.unmatched <= 0) return null;
  return (
    <div style={{ padding: '6px 20px', fontSize: 13, color: 'var(--text-muted)', borderBottom: '1px solid var(--border-subtle)' }}>
      {`${t.unmatched} sub-task${t.unmatched === 1 ? '' : 's'} outside the name rule, not listed here. `}
      <Link to="/ticket-check" search={{ check: 'rule' }}>See them in Ticket Check</Link>
    </div>
  );
}

function Head({ label, on, click, center }: { label: string; on: Dir | null; click: () => void; center?: boolean }) {
  const icon: IconName = on === 'asc' ? 'arrow-up' : on === 'desc' ? 'arrow-down' : 'arrow-up-down';
  return (
    <button type="button" onClick={click} className="hv-underline" style={{
      display: 'flex', alignItems: 'center', gap: 4, justifyContent: center ? 'center' : 'flex-start', padding: 0, border: 0, background: 'none',
      font: '600 12px/1.2 var(--font-text)', color: on ? 'var(--text-heading)' : 'var(--text-muted)', cursor: 'pointer', whiteSpace: 'nowrap',
    }}>
      {label}<span style={{ display: 'inline-flex', opacity: on ? 1 : 0.45 }}><Icon name={icon} size={12} /></span>
    </button>
  );
}

function Owner({ id }: { id?: string | null }) {
  const members = useMemberMap();
  const a = av(members[id ?? ''], id ?? '—');
  return (
    <span style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
      <Avatar a={a} size={24} fs={12} />
      <span className="ellipsis" style={{ fontSize: 14, color: 'var(--text-heading)' }}>{a.name}</span>
    </span>
  );
}

const nameStyle: CSSProperties = { font: '600 15px/1.3 var(--font-text)', color: 'var(--text-heading)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0 };

/** A coloured status cell or chip: red and outlined when late. Its text wraps rather than spill into the next column. */
const cellStyle = (status: string, late: boolean): CSSProperties => ({
  display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: 28, boxSizing: 'border-box', padding: '4px 6px', borderRadius: 6,
  cursor: 'pointer', font: '600 13px/1.2 var(--font-text)', textAlign: 'center', minWidth: 0, ...(late ? lateChip : { ...chip(status as Status), border: 0 }),
});

/** "WIP · 2d late"; in a narrow column the days late go to a second line. */
function LateLabel({ label, days }: { label: string; days: number }) {
  return <span>{label} ·{' '}<span style={{ whiteSpace: 'nowrap' }}>{days}d late</span></span>;
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

/** A function and its steps; a function on a shorter flow than the tab's (3 of 5) says it has no JM steps. */
function FunctionRow({ f, n }: { f: Fn; n: number }) {
  const active = jmRoundAt(f.steps);
  return (
    <div style={{ display: 'grid', gridTemplateColumns: fnCols(n), gap: '0 10px', alignItems: 'center', padding: '6px 20px', borderBottom: '1px solid var(--border-subtle)', minHeight: 44, boxSizing: 'border-box' }}>
      <span style={nameStyle} title={`${f.id} ${f.name}`}>{f.name}</span>
      <Owner id={f.ba_id} />
      {f.steps.map((c, i) => (c
        ? <StepCell key={i} c={c} active={i === active} />
        : <span key={i} style={{ textAlign: 'center', color: 'var(--text-muted)' }}>—</span>))}
      {f.steps.length < n && (
        <span style={{ gridColumn: `span ${n - f.steps.length}`, textAlign: 'center', fontSize: 12, color: 'var(--text-muted)' }}>No JM steps</span>
      )}
    </div>
  );
}

/**
 * A step: its status (late: red, with the days late), or just Done; the step where the JM round is now is ringed, and a fix
 * step's review rounds are in its tooltip.
 */
function StepCell({ c, active }: { c: Step; active: boolean }) {
  const open = useOpenTicket();
  const members = useMemberMap();
  const st = statusOf(c.status);
  const closed = c.status === 'C';
  const late = c.late && !closed;
  const where = c.with_jm ? 'JM' : 'Team';
  const stepName = STEPS.find((s) => s.k === c.step)?.label ?? c.step;
  const now = active ? ` · now: ${c.step === 'jmr' ? 'waiting for the JM review' : 'fixing JM comments'}` : '';
  const tip = `${c.key} · ${stepName} · ${st.label}${late ? ` · ${c.late_days} working days late` : ''} · ${members[c.assignee_id ?? '']?.name ?? 'unassigned'}`
    + ` · plan ${dm(c.planned_start) || '?'} – ${dm(c.planned_end) || '?'}${c.rounds ? ` · round ${c.rounds}, with ${where}` : ''}${now}`;
  return (
    <button type="button" title={tip} onClick={() => open(c.key)} className="hv-bright" style={{ ...cellStyle(c.status, late), boxShadow: active ? ACTIVE_RING : undefined }}>
      {closed ? 'Done' : late ? <LateLabel label={st.label} days={c.late_days} /> : st.label}
    </button>
  );
}

/** A single ticket; a done one shows just Done, without its date. */
function TicketRow({ t }: { t: Ticket }) {
  const open = useOpenTicket();
  const st = statusOf(t.status);
  const done = t.status === 'C';
  const late = t.late && !done;
  return (
    <div style={{ display: 'grid', gridTemplateColumns: ONE_COLS, gap: '0 10px', alignItems: 'center', padding: '6px 20px', borderBottom: '1px solid var(--border-subtle)', minHeight: 44, boxSizing: 'border-box' }}>
      <span style={nameStyle} title={`${t.key} ${t.name}`}>{t.name}</span>
      <Owner id={t.assignee_id} />
      <button type="button" onClick={() => open(t.key)} title={`${t.key} · ${st.label}${late ? ` · ${t.late_days} working days late` : ''}`} className="hv-bright" style={cellStyle(t.status, late)}>
        {done ? 'Done' : late ? <LateLabel label={st.label} days={t.late_days} /> : st.label}
      </button>
      <span style={{ font: '500 14px/1 var(--font-mono)', color: late ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{done ? '' : dm(t.planned_end)}</span>
    </div>
  );
}
