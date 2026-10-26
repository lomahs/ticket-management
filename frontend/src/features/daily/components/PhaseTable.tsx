import { Link } from '@tanstack/react-router';
import { useState, type CSSProperties } from 'react';
import { useMemberMap } from '../../../shared/api/queries';
import type { Schemas, Status } from '../../../shared/api/types';
import { Icon, IconButton, type IconName } from '../../../shared/ds';
import { dm } from '../../../shared/format/dates';
import { STEPS, statusOf } from '../../../shared/format/labels';
import { useOpenTicket } from '../../../shared/state/ticket';
import { Avatar, av } from '../../../shared/ui/bits';
import { chip, Legend, lateChip } from './chips';

type Group = Schemas['DailyGroup'];
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

/** The phase groups that have started: a done group is one row, a current one lists its functions and single tickets. */
export function PhaseTable({ groups }: { groups: Group[] }) {
  const [closed, setClosed] = useState<string[]>([]);
  const [fnSort, setFnSort] = useState<Sort<FnKey> | null>(null);
  const [oneSort, setOneSort] = useState<Sort<OneKey> | null>(null);
  const toggle = (g: string) => setClosed((c) => (c.includes(g) ? c.filter((x) => x !== g) : [...c, g]));
  return (
    <section className="card" aria-label="Phases and functions" style={{ overflow: 'hidden', borderRadius: 14, padding: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap', padding: '16px 20px', borderBottom: '1px solid var(--border-subtle)' }}>
        <span style={{ font: '700 18px/1.2 var(--font-display)', color: 'var(--text-heading)' }}>Phases &amp; functions</span>
        <span className="spacer" />
        <Legend />
      </div>
      {!groups.length ? <div className="empty-row" style={{ borderTop: 0 }}>No phase has started yet.</div> : (
        <div style={{ overflowX: 'auto' }}>
          <div style={{ minWidth: 1080 }}>
            {groups.map((g) => {
              const open = g.state === 'current' && !closed.includes(g.name);
              return (
                <div key={g.name}>
                  <GroupRow g={g} open={open} toggle={() => toggle(g.name)} />
                  {open && g.sub_phases.map((s) => (
                    <SubPhase key={s.key} s={s} label={g.sub_phases.length > 1} fnSort={fnSort} setFnSort={setFnSort} oneSort={oneSort} setOneSort={setOneSort} />
                  ))}
                </div>
              );
            })}
          </div>
        </div>
      )}
    </section>
  );
}

function GroupRow({ g, open, toggle }: { g: Group; open: boolean; toggle: () => void }) {
  const done = g.state === 'done';
  return (
    <div style={{ display: 'grid', gridTemplateColumns: FN_COLS, gap: '0 10px', alignItems: 'center', minHeight: 44, padding: '4px 20px 4px 12px', background: 'var(--surface-hover)', borderBottom: '1px solid var(--border-subtle)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
        {done ? <span style={{ width: 28, flex: 'none' }} />
          : <IconButton icon={open ? 'chevron-down' : 'chevron-right'} label={open ? `Collapse ${g.name}` : `Expand ${g.name}`} size="sm" onClick={toggle} />}
        <span style={{ font: '700 16px/1.3 var(--font-text)', color: 'var(--text-heading)' }}>{g.name}</span>
      </div>
      <div style={{ gridColumn: '2 / span 6', display: 'flex', alignItems: 'center', gap: 12 }}>
        <div style={{ width: 160, height: 8, borderRadius: 99, background: 'var(--border-subtle)', overflow: 'hidden', flex: 'none' }}>
          <div style={{ height: 8, width: g.pct + '%', background: 'var(--text-heading)' }} />
        </div>
        <span style={{ font: '700 14px/1 var(--font-mono)', color: 'var(--text-heading)', minWidth: 40 }}>{g.pct}%</span>
        <span style={{ fontSize: 14, color: 'var(--text-muted)' }}>
          {g.closed} / {g.total} done{g.late > 0 && <b style={{ color: 'var(--hl-red-fg)' }}> · {g.late} late</b>}
        </span>
        <span className="spacer" />
        {done && <span style={{ ...chip('C'), padding: '4px 12px', font: '700 13px/1.3 var(--font-text)' }}>Done</span>}
      </div>
    </div>
  );
}

function SubPhase({ s, label, fnSort, setFnSort, oneSort, setOneSort }: {
  s: Schemas['DailySubPhase']; label: boolean;
  fnSort: Sort<FnKey> | null; setFnSort: (s: Sort<FnKey> | null) => void; oneSort: Sort<OneKey> | null; setOneSort: (s: Sort<OneKey> | null) => void;
}) {
  const members = useMemberMap();
  const name = (id?: string | null) => members[id ?? '']?.name ?? id ?? '';
  const fns = sorted(s.functions, fnSort, (f: Fn, k) => (k === 'name' ? f.name.toLowerCase() : name(f.owner_id).toLowerCase()));
  const ones = sorted(s.tickets, oneSort, (t: Ticket, k) => (
    k === 'name' ? t.name.toLowerCase() : k === 'owner' ? name(t.assignee_id).toLowerCase() : k === 'status' ? STATUS_ORDER[t.status] ?? 0
      : k === 'start' ? t.planned_start ?? '9999' : t.planned_end ?? '9999'));
  const empty = !s.functions.length && !s.tickets.length;
  return (
    <div>
      {label && <div style={{ padding: '12px 20px 2px 46px', font: '600 12px/1 var(--font-text)', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '.04em' }}>{s.name}</div>}
      {s.functions.length > 0 && (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: FN_COLS, gap: '0 10px', padding: '10px 20px 6px 46px' }}>
            <Head label="Function" on={fnSort?.key === 'name' ? fnSort.dir : null} click={() => setFnSort(next(fnSort, 'name'))} />
            <Head label="Owner" on={fnSort?.key === 'owner' ? fnSort.dir : null} click={() => setFnSort(next(fnSort, 'owner'))} />
            {STEPS.map((st) => <span key={st.k} style={{ textAlign: 'center', font: '600 12px/1.2 var(--font-text)', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>{st.short}</span>)}
          </div>
          {fns.map((f) => <FunctionRow key={f.id} f={f} />)}
        </>
      )}
      {s.tickets.length > 0 && (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: ONE_COLS, gap: '0 10px', padding: '10px 20px 6px 46px' }}>
            {([['name', 'Task'], ['owner', 'Owner'], ['status', 'Status'], ['start', 'Start'], ['end', 'End']] as [OneKey, string][]).map(([k, l]) => (
              <Head key={k} label={l} on={oneSort?.key === k ? oneSort.dir : null} click={() => setOneSort(next(oneSort, k))} center={k === 'status'} />
            ))}
          </div>
          {ones.map((t) => <TicketRow key={t.key} t={t} />)}
        </>
      )}
      {(s.unmatched > 0 || empty) && (
        <div style={{ padding: '8px 20px 10px 46px', fontSize: 13, color: 'var(--text-muted)', borderBottom: '1px solid var(--border-subtle)' }}>
          {s.unmatched > 0 ? (
            <>
              {`${s.unmatched} sub-task${s.unmatched === 1 ? '' : 's'} outside the name rule, not listed here. `}
              <Link to="/ticket-check" search={{ check: 'rule' }}>See them in Ticket Check</Link>
            </>
          ) : 'No sub-tasks yet.'}
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

function FunctionRow({ f }: { f: Fn }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: FN_COLS, gap: '0 10px', alignItems: 'center', padding: '6px 20px 6px 46px', borderBottom: '1px solid var(--border-subtle)', minHeight: 48 }}>
      <span style={nameStyle} title={`${f.id} ${f.name}`}>{f.name}</span>
      <Owner id={f.owner_id} />
      {f.steps.map((c, i) => (c
        ? <StepCell key={i} c={c} prev={i > 0 ? f.steps[i - 1] : null} next={i < f.steps.length - 1 ? f.steps[i + 1] : null} />
        : <span key={i} style={{ textAlign: 'center', color: 'var(--text-muted)' }}>—</span>))}
    </div>
  );
}

/** A step: a node on the line from Create to Fix comment JM, its status (late in red) and planned end; a fix step shows its review rounds. */
function StepCell({ c, prev, next: after }: { c: Step; prev: Step | null; next: Step | null }) {
  const open = useOpenTicket();
  const members = useMemberMap();
  const st = statusOf(c.status);
  const tone = chip(c.status as Status);
  const closed = c.status === 'C';
  const label = c.late ? `Late ${c.late_days}d` : st.label;
  const fg = c.late ? 'var(--hl-red-fg)' : tone.color;
  const node: CSSProperties = closed ? { background: 'var(--do-clo)', border: 0 }
    : c.status === 'R' ? { background: 'var(--do-res)', border: 0 }
      : c.status === 'W' ? { background: 'var(--do-wip)', border: 0 }
        : { background: 'var(--surface-card)', border: '2px solid var(--do-nys)' };
  const line = (done: boolean) => (done ? 'var(--do-clo)' : 'var(--border-strong)');
  const where = c.with_jm ? 'JM' : 'Team';
  const stepName = STEPS.find((s) => s.k === c.step)?.label ?? c.step;
  const tip = `${c.key} · ${stepName} · ${st.label}${c.late ? ` · ${c.late_days} working days late` : ''} · ${members[c.assignee_id ?? '']?.name ?? 'unassigned'}`
    + ` · plan ${dm(c.planned_start) || '?'} – ${dm(c.planned_end) || '?'}${c.rounds ? ` · round ${c.rounds}, with ${where}` : ''}`;
  return (
    <button type="button" title={tip} onClick={() => open(c.key)} className="hv-bg" style={{
      position: 'relative', alignSelf: 'stretch', display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', gap: 4,
      padding: '6px 0', border: 0, borderRadius: 6, background: 'transparent', color: 'inherit', cursor: 'pointer',
    }}>
      {prev && <span style={{ position: 'absolute', top: 15, left: -5, right: '50%', height: 2, background: line(prev.status === 'C') }} />}
      {after && <span style={{ position: 'absolute', top: 15, left: '50%', right: -5, height: 2, background: line(closed) }} />}
      {c.rounds > 0 && (
        <span style={{
          position: 'absolute', top: 6, left: -5, transform: 'translateX(-50%)', zIndex: 1, display: 'flex', alignItems: 'center', gap: 3, height: 20, padding: '0 7px',
          borderRadius: 99, boxShadow: '0 0 0 2px var(--surface-card)', font: '700 11px/1 var(--font-text)', whiteSpace: 'nowrap',
          background: c.with_jm ? 'var(--do-jm)' : 'var(--do-team-bg)', color: c.with_jm ? 'var(--do-jm-fg)' : 'var(--do-team)',
        }}>
          <Icon name="repeat" size={11} />R{c.rounds} · {where}
        </span>
      )}
      <span style={{
        position: 'relative', display: 'flex', alignItems: 'center', justifyContent: 'center', width: 20, height: 20, boxSizing: 'border-box', borderRadius: '50%',
        color: '#fff', boxShadow: c.late ? '0 0 0 3px var(--hl-red)' : undefined, ...node, ...(c.late && !closed ? { border: '2px solid var(--hl-red-fg)' } : {}),
      }}>
        {closed && <Icon name="check" size={12} />}
      </span>
      <span style={{ font: '600 12px/1.2 var(--font-text)', color: fg, whiteSpace: 'nowrap' }}>{label}</span>
      <span style={{ minHeight: 11, font: '500 11px/1 var(--font-mono)', color: c.late ? 'var(--hl-red-fg)' : 'var(--text-muted)', whiteSpace: 'nowrap' }}>{dm(c.planned_end)}</span>
    </button>
  );
}

function TicketRow({ t }: { t: Ticket }) {
  const open = useOpenTicket();
  const st = statusOf(t.status);
  const due = t.late ? 'var(--hl-red-fg)' : 'var(--text-heading)';
  return (
    <div style={{ display: 'grid', gridTemplateColumns: ONE_COLS, gap: '0 10px', alignItems: 'center', padding: '3px 20px 3px 46px', borderBottom: '1px solid var(--border-subtle)', minHeight: 38 }}>
      <span style={nameStyle} title={`${t.key} ${t.name}`}>{t.name}</span>
      <Owner id={t.assignee_id} />
      <button type="button" onClick={() => open(t.key)} title={`${t.key} · ${st.label}${t.late ? ` · ${t.late_days} working days late` : ''}`} className="hv-bright" style={{
        display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: 28, boxSizing: 'border-box', padding: '4px 6px', borderRadius: 6, cursor: 'pointer',
        font: '600 13px/1.2 var(--font-text)', ...(t.late ? lateChip : { ...chip(t.status as Status), border: 0 }),
      }}>
        {t.late ? `${st.label} · late ${t.late_days}d` : st.label}
      </button>
      <span style={{ font: '500 14px/1 var(--font-mono)', color: 'var(--text-muted)' }}>{dm(t.planned_start)}</span>
      <span style={{ font: '500 14px/1 var(--font-mono)', color: due }}>{dm(t.planned_end)}</span>
    </div>
  );
}
