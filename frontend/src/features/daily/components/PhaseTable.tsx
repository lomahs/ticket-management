import { Link } from '@tanstack/react-router';
import { useState, type CSSProperties } from 'react';
import { useMemberMap } from '../../../shared/api/queries';
import type { Schemas, Status } from '../../../shared/api/types';
import { Icon, IconButton, type IconName } from '../../../shared/ds';
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

const FN_COLS = 'minmax(200px,2fr) 120px repeat(5,minmax(0,1fr))';
const ONE_COLS = 'minmax(240px,1fr) 140px 120px 110px 110px';

type Dir = 'asc' | 'desc';
interface Sort<K extends string> { key: K; dir: Dir }
type FnKey = 'name' | 'owner';
type OneKey = 'name' | 'owner' | 'status' | 'start' | 'end';
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

/** A Task in progress; one of a snapshot taken before Tasks were rolled up has no state and counts as one. */
const inProgress = (t: Task) => t.state !== 'done' && t.state !== 'not_started';

/** The Tasks of the started phase groups; such a snapshot keeps a done group as one row with no Tasks, shown as is. */
function tasksOf(groups: Group[]): Task[] {
  return groups.flatMap((g) => (g.sub_phases.length ? g.sub_phases
    : [{ key: g.name, name: g.name, state: g.state, pct: g.pct, closed: g.closed, total: g.total, late: g.late, functions: [], tickets: [], unmatched: 0 }]));
}

/** The Tasks of the phase groups that have started, one collapsible group each: open while in progress. */
export function PhaseTable({ groups }: { groups: Group[] }) {
  const [flipped, setFlipped] = useState<string[]>([]);
  const [fnSort, setFnSort] = useState<Sort<FnKey> | null>(null);
  const [oneSort, setOneSort] = useState<Sort<OneKey> | null>(null);
  const toggle = (k: string) => setFlipped((f) => (f.includes(k) ? f.filter((x) => x !== k) : [...f, k]));
  const tasks = tasksOf(groups);
  return (
    <section className="card" aria-label="Phases and functions" style={{ overflow: 'hidden', borderRadius: 14, padding: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap', padding: '16px 20px', borderBottom: '1px solid var(--border-subtle)' }}>
        <span style={{ font: '700 18px/1.2 var(--font-display)', color: 'var(--text-heading)' }}>Phases &amp; functions</span>
        <span className="spacer" />
        <Legend />
      </div>
      {!tasks.length ? <div className="empty-row" style={{ borderTop: 0 }}>No phase has started yet.</div> : (
        <div style={{ overflowX: 'auto' }}>
          <div style={{ minWidth: 1080 }}>
            {tasks.map((t) => {
              const items = t.functions.length + t.tickets.length + t.unmatched > 0;
              const open = items && inProgress(t) !== flipped.includes(t.key);
              return (
                <div key={t.key}>
                  <TaskRow t={t} expandable={items} open={open} toggle={() => toggle(t.key)} />
                  {open && <SubPhase s={t} fnSort={fnSort} setFnSort={setFnSort} oneSort={oneSort} setOneSort={setOneSort} />}
                </div>
              );
            })}
          </div>
        </div>
      )}
    </section>
  );
}

function TaskRow({ t, expandable, open, toggle }: { t: Task; expandable: boolean; open: boolean; toggle: () => void }) {
  const badge = t.state === 'done' ? { s: 'C' as const, label: 'Done' } : t.state === 'not_started' ? { s: 'N' as const, label: 'Not started' } : null;
  return (
    <div style={{ display: 'grid', gridTemplateColumns: FN_COLS, gap: '0 10px', alignItems: 'center', minHeight: 36, padding: '2px 20px 2px 12px', background: 'var(--surface-hover)', borderBottom: '1px solid var(--border-subtle)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
        {expandable ? <IconButton icon={open ? 'chevron-down' : 'chevron-right'} label={open ? `Collapse ${t.name}` : `Expand ${t.name}`} size="sm" onClick={toggle} />
          : <span style={{ width: 28, flex: 'none' }} />}
        <span className="ellipsis" style={{ font: '700 15px/1.3 var(--font-text)', color: 'var(--text-heading)' }} title={t.key}>{t.name}</span>
      </div>
      <div style={{ gridColumn: '2 / span 6', display: 'flex', alignItems: 'center', gap: 12 }}>
        {t.total > 0 && (
          <>
            <div style={{ width: 160, height: 8, borderRadius: 99, background: 'var(--border-subtle)', overflow: 'hidden', flex: 'none' }}>
              <div style={{ height: 8, width: t.pct + '%', background: 'var(--text-heading)' }} />
            </div>
            <span style={{ font: '700 14px/1 var(--font-mono)', color: 'var(--text-heading)', minWidth: 40 }}>{t.pct}%</span>
            <span style={{ fontSize: 14, color: 'var(--text-muted)' }}>
              {t.closed} / {t.total} done{t.late > 0 && <b style={{ color: 'var(--hl-red-fg)' }}> · {t.late} late</b>}
            </span>
          </>
        )}
        <span className="spacer" />
        {badge && <span style={{ ...chip(badge.s), padding: '2px 10px', font: '700 12px/1.3 var(--font-text)' }}>{badge.label}</span>}
      </div>
    </div>
  );
}

function SubPhase({ s, fnSort, setFnSort, oneSort, setOneSort }: {
  s: Task;
  fnSort: Sort<FnKey> | null; setFnSort: (s: Sort<FnKey> | null) => void; oneSort: Sort<OneKey> | null; setOneSort: (s: Sort<OneKey> | null) => void;
}) {
  const members = useMemberMap();
  const name = (id?: string | null) => members[id ?? '']?.name ?? id ?? '';
  const fns = sorted(s.functions, fnSort, (f: Fn, k) => (k === 'name' ? f.name.toLowerCase() : name(f.owner_id).toLowerCase()));
  const ones = sorted(s.tickets, oneSort, (t: Ticket, k) => (
    k === 'name' ? t.name.toLowerCase() : k === 'owner' ? name(t.assignee_id).toLowerCase() : k === 'status' ? STATUS_ORDER[t.status] ?? 0
      : k === 'start' ? t.planned_start ?? '9999' : t.planned_end ?? '9999'));
  return (
    <div>
      {s.functions.length > 0 && (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: FN_COLS, gap: '0 10px', padding: '6px 20px 4px 46px' }}>
            <Head label="Function" on={fnSort?.key === 'name' ? fnSort.dir : null} click={() => setFnSort(next(fnSort, 'name'))} />
            <Head label="Owner" on={fnSort?.key === 'owner' ? fnSort.dir : null} click={() => setFnSort(next(fnSort, 'owner'))} />
            {STEPS.map((st) => <span key={st.k} style={{ textAlign: 'center', font: '600 12px/1.2 var(--font-text)', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>{st.short}</span>)}
          </div>
          {fns.map((f) => <FunctionRow key={f.id} f={f} />)}
        </>
      )}
      {s.tickets.length > 0 && (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: ONE_COLS, gap: '0 10px', padding: '6px 20px 4px 46px' }}>
            {([['name', 'Task'], ['owner', 'Owner'], ['status', 'Status'], ['start', 'Start'], ['end', 'End']] as [OneKey, string][]).map(([k, l]) => (
              <Head key={k} label={l} on={oneSort?.key === k ? oneSort.dir : null} click={() => setOneSort(next(oneSort, k))} center={k === 'status'} />
            ))}
          </div>
          {ones.map((t) => <TicketRow key={t.key} t={t} />)}
        </>
      )}
      {s.unmatched > 0 && (
        <div style={{ padding: '6px 20px 6px 46px', fontSize: 13, color: 'var(--text-muted)', borderBottom: '1px solid var(--border-subtle)' }}>
          {`${s.unmatched} sub-task${s.unmatched === 1 ? '' : 's'} outside the name rule, not listed here. `}
          <Link to="/ticket-check" search={{ check: 'rule' }}>See them in Ticket Check</Link>
        </div>
      )}
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

function FunctionRow({ f }: { f: Fn }) {
  const active = jmRoundAt(f.steps);
  return (
    <div style={{ display: 'grid', gridTemplateColumns: FN_COLS, gap: '0 10px', alignItems: 'center', padding: '0 20px 0 46px', borderBottom: '1px solid var(--border-subtle)', minHeight: 40 }}>
      <span style={nameStyle} title={`${f.id} ${f.name}`}>{f.name}</span>
      <Owner id={f.owner_id} />
      {f.steps.map((c, i) => (c
        ? <StepCell key={i} c={c} active={i === active} prev={i > 0 ? f.steps[i - 1] : null} next={i < f.steps.length - 1 ? f.steps[i + 1] : null} />
        : <span key={i} style={{ textAlign: 'center', color: 'var(--text-muted)' }}>—</span>))}
    </div>
  );
}

/**
 * A step: a node on the line from Create to Fix comment JM, its status (late in red) and planned end, or just Done; a fix step
 * with JM shows its review rounds, and the step where the JM round is now is ringed.
 */
function StepCell({ c, active, prev, next: after }: { c: Step; active: boolean; prev: Step | null; next: Step | null }) {
  const open = useOpenTicket();
  const members = useMemberMap();
  const st = statusOf(c.status);
  const tone = chip(c.status as Status);
  const closed = c.status === 'C';
  const late = c.late && !closed;
  const label = closed ? 'Done' : late ? `Late ${c.late_days}d` : st.label;
  const fg = late ? 'var(--hl-red-fg)' : tone.color;
  const node: CSSProperties = closed ? { background: 'var(--do-clo)', border: 0 }
    : c.status === 'R' ? { background: 'var(--do-res)', border: 0 }
      : c.status === 'W' ? { background: 'var(--do-wip)', border: 0 }
        : { background: 'var(--surface-card)', border: '2px solid var(--do-nys)' };
  const line = (done: boolean) => (done ? 'var(--do-clo)' : 'var(--border-strong)');
  const where = c.with_jm ? 'JM' : 'Team';
  const stepName = STEPS.find((s) => s.k === c.step)?.label ?? c.step;
  const now = active ? ` · now: ${c.step === 'jmr' ? 'waiting for the JM review' : 'fixing JM comments'}` : '';
  const tip = `${c.key} · ${stepName} · ${st.label}${late ? ` · ${c.late_days} working days late` : ''} · ${members[c.assignee_id ?? '']?.name ?? 'unassigned'}`
    + ` · plan ${dm(c.planned_start) || '?'} – ${dm(c.planned_end) || '?'}${c.rounds ? ` · round ${c.rounds}, with ${where}` : ''}${now}`;
  return (
    <button type="button" title={tip} onClick={() => open(c.key)} className="hv-bg" style={{
      position: 'relative', alignSelf: 'stretch', display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', gap: 2,
      padding: '3px 0', border: 0, borderRadius: 6, background: active ? 'var(--surface-tint)' : 'transparent', color: 'inherit', cursor: 'pointer',
    }}>
      {prev && <span style={{ position: 'absolute', top: 12, left: -5, right: '50%', height: 2, background: line(prev.status === 'C') }} />}
      {after && <span style={{ position: 'absolute', top: 12, left: '50%', right: -5, height: 2, background: line(closed) }} />}
      {c.rounds > 0 && c.with_jm && (
        <span style={{
          position: 'absolute', top: 3, left: -5, transform: 'translateX(-50%)', zIndex: 1, display: 'flex', alignItems: 'center', gap: 3, height: 20, padding: '0 7px',
          borderRadius: 99, boxShadow: '0 0 0 2px var(--surface-card)', font: '700 11px/1 var(--font-text)', whiteSpace: 'nowrap',
          background: 'var(--do-jm)', color: 'var(--do-jm-fg)',
        }}>
          <Icon name="repeat" size={11} />R{c.rounds} · {where}
        </span>
      )}
      <span style={{
        position: 'relative', display: 'flex', alignItems: 'center', justifyContent: 'center', width: 20, height: 20, boxSizing: 'border-box', borderRadius: '50%',
        color: '#fff', boxShadow: active ? ACTIVE_RING : late ? '0 0 0 3px var(--hl-red)' : undefined, ...node, ...(late ? { border: '2px solid var(--hl-red-fg)' } : {}),
      }}>
        {closed && <Icon name="check" size={12} />}
      </span>
      <span style={{ font: '600 12px/1.2 var(--font-text)', color: fg, whiteSpace: 'nowrap' }}>{label}</span>
      <span style={{ minHeight: 11, font: '500 11px/1 var(--font-mono)', color: late ? 'var(--hl-red-fg)' : 'var(--text-muted)', whiteSpace: 'nowrap' }}>{closed ? '' : dm(c.planned_end)}</span>
    </button>
  );
}

/** A single ticket; a done one shows just Done, without its dates. */
function TicketRow({ t }: { t: Ticket }) {
  const open = useOpenTicket();
  const st = statusOf(t.status);
  const done = t.status === 'C';
  const due = t.late ? 'var(--hl-red-fg)' : 'var(--text-heading)';
  return (
    <div style={{ display: 'grid', gridTemplateColumns: ONE_COLS, gap: '0 10px', alignItems: 'center', padding: '2px 20px 2px 46px', borderBottom: '1px solid var(--border-subtle)', minHeight: 32 }}>
      <span style={nameStyle} title={`${t.key} ${t.name}`}>{t.name}</span>
      <Owner id={t.assignee_id} />
      <button type="button" onClick={() => open(t.key)} title={`${t.key} · ${st.label}${t.late ? ` · ${t.late_days} working days late` : ''}`} className="hv-bright" style={{
        display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: 24, boxSizing: 'border-box', padding: '4px 6px', borderRadius: 6, cursor: 'pointer',
        font: '600 13px/1.2 var(--font-text)', ...(t.late && !done ? lateChip : { ...chip(t.status as Status), border: 0 }),
      }}>
        {done ? 'Done' : t.late ? `${st.label} · late ${t.late_days}d` : st.label}
      </button>
      <span style={{ font: '500 14px/1 var(--font-mono)', color: 'var(--text-muted)' }}>{done ? '' : dm(t.planned_start)}</span>
      <span style={{ font: '500 14px/1 var(--font-mono)', color: due }}>{done ? '' : dm(t.planned_end)}</span>
    </div>
  );
}
