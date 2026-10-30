import { useQuery } from '@tanstack/react-query';
import { getRouteApi, Link } from '@tanstack/react-router';
import { useState, type MouseEvent } from 'react';
import type { Schemas } from '../../../shared/api/types';
import { useMemberMap } from '../../../shared/api/queries';
import type { TaskCard, Tone } from '../../../shared/api/types';
import { Button, Icon, IconButton, TextField } from '../../../shared/ds';
import { dm, f1, hrs } from '../../../shared/format/dates';
import { STEPS, TONE, statusOf } from '../../../shared/format/labels';
import { useOpenTicket, useTicketKey } from '../../../shared/state/ticket';
import { Avatar, av, Dot } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { matrixQuery } from '../api';
import {
  anyFilter, applyFilter, filterTickets, fromSearch, matchesText, pendingAtStep, stepRange, summaryCounts, toSearch, type MatrixFilter,
} from '../model/filters';
import { applyLayout, useMatrixLayout, type MatrixLayout } from '../model/layout';
import { MatrixHeader } from './MatrixHeader';

const route = getRouteApi('/app/anken/$epic/matrix');
const SUM_GRID = '150px repeat(3,minmax(0,1fr)) 12px minmax(0,1fr) 80px';
const MX_GRID = '250px repeat(5,minmax(150px,1fr))';
const SCOLS: { k: string; kind: 'status' | 'prog'; label: string; dot: string; col: string; count: 'N' | 'W' | 'D' | 'late' }[] = [
  { k: 'N', kind: 'status', label: 'NYS', dot: 'var(--tn-nys)', col: '2', count: 'N' },
  { k: 'W', kind: 'status', label: 'WIP', dot: 'var(--tn-ontime)', col: '3', count: 'W' },
  { k: 'D', kind: 'status', label: 'Done', dot: 'var(--tn-clo)', col: '4', count: 'D' },
  { k: 'late', kind: 'prog', label: 'Late', dot: 'var(--tn-delay)', col: '6', count: 'late' },
];

const splitTasks = (s?: string) => (s ? s.split(',').filter(Boolean) : undefined);

export function MatrixPage() {
  const { epic } = route.useParams();
  const { tasks, phase } = route.useSearch();
  const [layout] = useMatrixLayout(epic);
  const q = useQuery(matrixQuery(epic, splitTasks(tasks), phase, layout?.shown));
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, minWidth: 0 }}>
      <MatrixHeader epic={epic} title={q.data?.title} phase={q.data?.phase} tab="matrix" />
      {q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : <Matrix m={q.data} layout={layout} />}
    </div>
  );
}

function Matrix({ m, layout }: { m: Schemas['MatrixView']; layout: MatrixLayout | null }) {
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const members = useMemberMap();
  const f = fromSearch(search);
  const setF = (patch: Partial<MatrixFilter>) => navigate({
    search: (p) => ({ ticket: p.ticket, anken: p.anken, tasks: p.tasks, phase: p.phase, ...toSearch({ ...f, ...patch }) }), replace: true,
  });
  const [sumOpen, setSumOpen] = useState(true);
  const [rangeOpen, setRangeOpen] = useState<{ i: number; top: number; left: number } | null>(null);

  const rows = applyFilter(m.functions, f);
  const tickets = filterTickets(m.tickets, f);
  const pool = m.functions.filter((r) => matchesText(r, f.q));
  const ids = Object.keys(members);
  const bas = ids.filter((id) => members[id].role === 'BA' && pool.some((r) => r.ba_id === id));
  const bals = ids.filter((id) => members[id].role === 'BAL' && pool.some((r) => r.bal_id === id));
  const summary = summaryCounts(pool, bas, bals);
  const toggle = (list: string[], v: string) => (list.includes(v) ? list.filter((x) => x !== v) : [...list, v]);
  const s = m.stats;
  const stats = [
    { label: 'Progress', value: s.progress_pct + '%', sub: `${s.functions_done}/${s.functions_total} functions done` },
    { label: 'Late functions', value: s.late_functions, sub: `${s.late_subtasks} sub-task`, color: 'var(--hl-red-fg)' },
    { label: 'Over estimate', value: s.over_subtasks, sub: 'sub-task' },
    { label: 'Effort', value: hrs(s.logged_h), sub: `/ ${hrs(s.estimate_h)} estimate` },
  ];
  const openRange = (i: number, e: MouseEvent<HTMLButtonElement>) => {
    const b = e.currentTarget.getBoundingClientRect();
    setRangeOpen((cur) => (cur?.i === i ? null : { i, top: b.bottom + 6, left: Math.max(8, Math.min(b.left, innerWidth - 370)) }));
  };
  const setRange = (i: number, k: 'from' | 'to', v: string) => {
    const r = { ...(f.ranges[i] ?? {}), [k]: v || undefined };
    const ranges = { ...f.ranges, [i]: r };
    if (!r.from && !r.to) delete ranges[i];
    setF({ ranges });
  };

  // One matrix per phase, in this browser's order; the Tasks (sub-phases) of a phase share its header.
  const shown = applyLayout(m.parents, layout).filter((p) => m.task_keys.includes(p.key));
  const phases = [...new Set(shown.map((t) => t.group))];
  const fromLink = !!(search.tasks || search.phase);
  const picker = m.parents.length > 0 && (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', fontSize: 12, color: 'var(--text-muted)' }}>
      <Icon name="list-todo" size={14} />
      <span>Showing <b className="heading">{phases.join(', ') || '–'}</b> · {shown.length} of {m.parents.length} sub-phases{fromLink ? ' (from the link)' : ''}</span>
      {fromLink && <Link to="/anken/$epic/matrix" params={{ epic: m.epic_key }}>Show the saved layout</Link>}
      <Link to="/anken/$epic/matrix/phases" params={{ epic: m.epic_key }}>Change in Setting phase</Link>
    </div>
  );

  if (!m.functions.length && !m.tickets.length) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        {picker}
        <div className="card" style={{ padding: 24, color: 'var(--text-muted)' }}>
          {m.parents.some((p) => p.functions > 0)
            ? <>The shown sub-phases have no functions yet. Choose others in <Link to="/anken/$epic/matrix/phases" params={{ epic: m.epic_key }}>Setting phase</Link>.</>
            : <>No sub-tasks in this anken follow the naming rule yet. Check <Link to="/settings/$tab" params={{ tab: 'name-rules' }}>Settings → Name rules</Link>.</>}
        </div>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {picker}
      <div className="card" style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'stretch', overflow: 'hidden' }}>
        {stats.map((x, i) => (
          <div key={x.label} style={{ flex: '1 1 140px', padding: '10px 16px', borderLeft: i ? '1px solid var(--border-subtle)' : 0 }}>
            <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{x.label}</div>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
              <span style={{ font: '700 20px/1.3 var(--font-display)', color: x.color ?? 'var(--text-heading)' }}>{x.value}</span>
              <span className="eyebrow">{x.sub}</span>
            </div>
          </div>
        ))}
      </div>

      <section className="card" aria-label="Status summary" style={{ display: 'flex', flexDirection: 'column', gap: 10, padding: '14px 16px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <IconButton icon={sumOpen ? 'chevron-up' : 'chevron-down'} label={sumOpen ? 'Hide summary' : 'Show summary'} size="sm" onClick={() => setSumOpen(!sumOpen)} style={{ marginLeft: -6 }} />
          <span className="title">Status summary</span>
          <span className="eyebrow">Functions by current step · click a column, member or count to filter</span>
          <span className="spacer" />
          {anyFilter(f) && <Button appearance="subtle" size="sm" iconBefore="x" onClick={() => navigate({ search: {}, replace: true })}>Clear filters</Button>}
          <span className="eyebrow">{rows.length}/{m.functions.length} function</span>
        </div>
        {sumOpen && (
          <div style={{ overflowX: 'auto' }}>
            <div style={{ minWidth: 560, display: 'flex', flexDirection: 'column', gap: 2 }}>
              <div style={{ display: 'grid', gridTemplateColumns: SUM_GRID, gap: 2, alignItems: 'stretch' }}>
                <span style={{ padding: '6px 10px', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)' }}>Member</span>
                {SCOLS.map((c) => {
                  const list = c.kind === 'status' ? f.status : f.prog;
                  const on = list.includes(c.k);
                  return (
                    <button key={c.k} className="hv-bg" aria-pressed={on} onClick={() => setF(c.kind === 'status' ? { status: toggle(f.status, c.k) } : { prog: toggle(f.prog, c.k) })} style={{
                      display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6, height: 30, padding: '0 6px', border: 0, borderRadius: 999,
                      background: on ? 'var(--surface-tint)' : 'transparent', boxShadow: on ? 'inset 0 0 0 1px var(--border-focus)' : 'none',
                      color: 'var(--text-heading)', font: '600 11px/1 var(--font-text)', cursor: 'pointer', gridColumn: c.col,
                    }}><Dot c={c.dot} />{c.label}</button>
                  );
                })}
                <span style={{ gridColumn: 7, padding: '6px 10px', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)', textAlign: 'center' }}>Total</span>
              </div>
              {summary.map((r, idx) => {
                const mem = r.memberId ? members[r.memberId] : null;
                const rowOn = !!r.memberId && f.members.includes(r.memberId);
                const sep = !r.memberId || (r.role === 'BAL' && summary[idx - 1]?.role === 'BA');
                return (
                  <div key={r.memberId ?? 'all'} style={{ display: 'grid', gridTemplateColumns: SUM_GRID, gap: 2, alignItems: 'stretch', borderTop: sep ? '1px solid var(--border-subtle)' : 0 }}>
                    <button className="hv-bg" aria-pressed={rowOn} onClick={() => setF({ members: r.memberId ? toggle(f.members, r.memberId) : [] })} style={{
                      display: 'flex', alignItems: 'center', gap: 8, height: 34, padding: '0 10px', border: 0, borderRadius: 10,
                      background: rowOn ? 'var(--surface-tint)' : 'transparent', color: 'var(--text-heading)',
                      font: `${rowOn || !r.memberId ? 700 : 500} 12px/1 var(--font-text)`, cursor: 'pointer', textAlign: 'left',
                    }}>
                      <Avatar a={mem ? av(mem) : { ini: 'Σ', color: 'var(--text-muted)' }} size={20} />
                      {mem ? mem.name : 'All'}
                      {r.role && <span style={{ font: '600 10px/16px var(--font-text)', padding: '0 6px', borderRadius: 999, background: 'var(--surface-hover)', color: 'var(--text-muted)' }}>{r.role}</span>}
                    </button>
                    {SCOLS.map((c) => {
                      const n = r.counts[c.count];
                      const list = c.kind === 'status' ? f.status : f.prog;
                      const on = list.includes(c.k) && (r.memberId ? f.members.includes(r.memberId) : !f.members.length);
                      const hot = c.k === 'late' && n > 0;
                      const pick = () => {
                        const same = on && list.length === 1;
                        setF(same ? { members: [], status: [], prog: [] }
                          : { members: r.memberId ? [r.memberId] : [], status: c.kind === 'status' ? [c.k] : [], prog: c.kind === 'prog' ? [c.k] : [] });
                      };
                      return (
                        <button key={c.k} className="hv-ring" onClick={pick} aria-label={`${mem?.name ?? 'All'} ${c.label}: ${n}`} style={{
                          height: 34, border: 0, borderRadius: 10, cursor: 'pointer', gridColumn: c.col,
                          background: on ? 'var(--surface-tint)' : hot ? 'var(--hl-red)' : 'var(--surface-hover)',
                          boxShadow: on ? 'inset 0 0 0 2px var(--border-focus)' : 'none',
                          color: n ? (hot ? 'var(--hl-red-fg)' : 'var(--text-heading)') : 'var(--text-disabled)', font: `${n ? 700 : 400} 13px/1 var(--font-mono)`,
                        }}>{n || '–'}</button>
                      );
                    })}
                    <span style={{ gridColumn: 7, display: 'flex', alignItems: 'center', justifyContent: 'center', font: '700 13px/1 var(--font-mono)', color: 'var(--text-heading)' }}>{r.total}</span>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </section>

      <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap', fontSize: 12, color: 'var(--text-muted)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '6px 14px', flexWrap: 'wrap' }}>
          <ToneSwatch tone="nys" label="NYS" />
          <span style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '3px 10px 3px 8px', borderRadius: 999, border: '1px solid var(--border-subtle)', flexWrap: 'wrap' }}>
            <span style={{ fontWeight: 700, color: 'var(--text-heading)' }} title="Late start and Delay also apply to a step not started yet, drawn with a dashed outline">WIP / NYS</span>
            {(['ontime', 'early', 'latestart', 'delay'] as const).map((k) => <ToneSwatch key={k} tone={k} label={TONE[k]} />)}
          </span>
          <ToneSwatch tone="res" label="Resolved" />
          <ToneSwatch tone="clo" label="Closed" />
        </div>
        <span className="spacer" />
        <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}><OverBadge />Over estimate</span>
      </div>

      {phases.map((g) => {
        const tasks = shown.filter((t) => t.group === g);
        const keys = new Set(tasks.map((t) => t.key));
        return (
          <PhaseMatrix key={g} phase={g} tasks={tasks} rows={rows.filter((r) => keys.has(r.task_key))}
            all={m.functions.filter((r) => keys.has(r.task_key))} tickets={tickets.filter((t) => keys.has(t.task_key))}
            allTickets={m.tickets.filter((t) => keys.has(t.task_key))} f={f} setF={setF} openRange={openRange} />
        );
      })}
      {!rows.length && !tickets.length && <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-muted)' }}>No functions match the filters.</div>}

      {rangeOpen && (
        <>
          <div onClick={() => setRangeOpen(null)} style={{ position: 'fixed', inset: 0, zIndex: 55 }} />
          <div role="dialog" aria-label="Date range" style={{
            position: 'fixed', top: rangeOpen.top, left: rangeOpen.left, zIndex: 56, padding: 12, display: 'flex', flexDirection: 'column', gap: 10,
            borderRadius: 14, background: 'var(--surface-card)', boxShadow: 'var(--shadow-overlay)',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <TextField type="date" value={f.ranges[rangeOpen.i]?.from ?? ''} onChange={(v) => setRange(rangeOpen.i, 'from', v)} style={{ width: 150 }} aria-label="From" />
              <span className="muted">–</span>
              <TextField type="date" value={f.ranges[rangeOpen.i]?.to ?? ''} onChange={(v) => setRange(rangeOpen.i, 'to', v)} style={{ width: 150 }} aria-label="To" />
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 6 }}>
              <Button appearance="subtle" size="sm" onClick={() => { const ranges = { ...f.ranges }; delete ranges[rangeOpen.i]; setF({ ranges }); setRangeOpen(null); }}>Clear</Button>
              <Button size="sm" onClick={() => setRangeOpen(null)}>Done</Button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

/** The matrix of one phase: one header, then a section per Task (sub-phase), then the pending counts of the phase. */
function PhaseMatrix({ phase, tasks, rows, all, tickets, allTickets, f, setF, openRange }: {
  phase: string; tasks: Schemas['ParentOption'][]; rows: Schemas['FunctionRow'][]; all: Schemas['FunctionRow'][];
  tickets: Schemas['MatrixTicket'][]; allTickets: Schemas['MatrixTicket'][];
  f: MatrixFilter; setF: (patch: Partial<MatrixFilter>) => void; openRange: (i: number, e: MouseEvent<HTMLButtonElement>) => void;
}) {
  const pending = pendingAtStep(rows);
  return (
    <section className="card" aria-label={`${phase} matrix`} style={{ overflowX: 'auto' }}>
      <div style={{ minWidth: 1010, display: 'flex', alignItems: 'baseline', gap: 10, padding: '10px 14px', borderBottom: '1px solid var(--border-subtle)' }}>
        <span className="title">{phase}</span>
        <span className="eyebrow">
          {rows.length}/{all.length} function{allTickets.length ? ` · ${tickets.length}/${allTickets.length} single ticket` : ''} · {tasks.length} sub-phase{tasks.length === 1 ? '' : 's'}
        </span>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: MX_GRID, minWidth: 1010, borderBottom: '1px solid var(--border-subtle)', background: 'var(--surface-hover)' }}>
        <div style={{ padding: '8px 12px 8px 14px', display: 'flex', flexDirection: 'column', gap: 6 }}>
          <span style={{ fontWeight: 600, color: 'var(--text-heading)' }}>Function</span>
          <TextField size="sm" iconBefore="search" placeholder="Search functions…" value={f.q} onChange={(v) => setF({ q: v })} aria-label="Search functions" />
        </div>
        {STEPS.map((st, i) => {
          const rg = f.ranges[i];
          const hasR = !!(rg && (rg.from || rg.to));
          const span = stepRange(rows, i);
          const sd = f.sort?.i === i ? f.sort.dir : 0;
          return (
            <div key={st.k} style={{ position: 'relative', padding: '8px 12px', borderLeft: '1px solid var(--border-subtle)', display: 'flex', flexDirection: 'column', gap: 6 }}>
              <button onClick={() => setF({ sort: sd === 0 ? { i, dir: 1 } : sd === 1 ? { i, dir: -1 } : null })} title="Sort by Planned Start" style={{
                display: 'flex', alignItems: 'center', gap: 6, alignSelf: 'flex-start', height: 19, padding: 0, border: 0, background: 'transparent',
                color: 'var(--text-heading)', font: '600 13px/19px var(--font-text)', cursor: 'pointer', whiteSpace: 'nowrap',
              }}>
                {st.label}
                <span style={{ display: 'flex', color: sd ? 'var(--text-heading)' : 'var(--text-muted)' }}>
                  <Icon name={sd === 1 ? 'arrow-up' : sd === -1 ? 'arrow-down' : 'arrow-up-down'} size={13} />
                </span>
              </button>
              <button className="hv-bd" onClick={(e) => openRange(i, e)} title="Filter by Planned Start – End range" style={{
                display: 'flex', alignItems: 'center', gap: 6, height: 30, padding: '0 10px', borderRadius: 999,
                border: `1px solid ${hasR ? 'var(--border-focus)' : 'var(--border-strong)'}`, background: hasR ? 'var(--surface-tint)' : 'var(--surface-card)',
                color: 'var(--text-heading)', font: '500 11px/1 var(--font-mono)', cursor: 'pointer', whiteSpace: 'nowrap',
              }}>
                <Icon name="calendar" size={12} />
                <span style={{ flex: 1, textAlign: 'left' }}>
                  {hasR ? `${rg.from ? dm(rg.from) : '…'} – ${rg.to ? dm(rg.to) : '…'}` : span ? `${dm(span[0])} – ${dm(span[1])}` : '–'}
                </span>
                <Icon name="chevron-down" size={12} />
              </button>
            </div>
          );
        })}
      </div>

      {tasks.map((t) => {
        const own = rows.filter((r) => r.task_key === t.key);
        const total = all.filter((r) => r.task_key === t.key).length;
        const ones = tickets.filter((x) => x.task_key === t.key);
        const onesTotal = allTickets.filter((x) => x.task_key === t.key).length;
        return (
          <div key={t.key} role="rowgroup" aria-label={t.name || t.summary}>
            <div title={t.summary} style={{ minWidth: 1010, boxSizing: 'border-box', display: 'flex', alignItems: 'center', gap: 8, padding: '7px 14px', borderBottom: '1px solid var(--border-subtle)', background: 'color-mix(in oklch, var(--surface-tint) 55%, var(--surface-card))' }}>
              <Icon name="list-todo" size={14} />
              <span style={{ fontWeight: 700, color: 'var(--text-heading)' }}>{t.name || t.summary}</span>
              <span className="key">{t.key}</span>
              <span title="Earliest Planned Start – latest Planned End of the sub-phase's sub-tasks" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, font: '500 11px/1 var(--font-mono)', color: 'var(--text-heading)' }}>
                <Icon name="calendar" size={12} />{t.start || t.end ? `${t.start ? dm(t.start) : '…'} – ${t.end ? dm(t.end) : '…'}` : 'No dates'}
              </span>
              <span className="eyebrow">{own.length}/{total} function{onesTotal ? ` · ${ones.length}/${onesTotal} single ticket` : ''}</span>
            </div>
            {own.map((r) => <FnRow key={r.task_key + '/' + r.id} r={r} />)}
            {ones.map((x) => <TicketRow key={x.card.key} t={x} />)}
            {!own.length && !ones.length && (
              <div style={{ minWidth: 1010, padding: '10px 14px', fontSize: 12, color: 'var(--text-muted)', borderBottom: '1px solid var(--border-subtle)' }}>
                {total || onesTotal ? 'No functions match the filters.' : 'No functions under this Task yet.'}
              </div>
            )}
          </div>
        );
      })}

      <div style={{ display: 'grid', gridTemplateColumns: MX_GRID, minWidth: 1010, background: 'var(--surface-hover)' }}>
        <div style={{ padding: '10px 14px' }}>
          <div style={{ fontWeight: 600, color: 'var(--text-heading)' }}>Pending at step</div>
          <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>Functions whose current step is this column</div>
        </div>
        {pending.map((p, i) => (
          <div key={i} style={{ margin: '4px 3px', padding: '8px 10px', borderRadius: 10, display: 'flex', flexDirection: 'column', gap: 4, background: p.bottleneck ? 'var(--surface-tint)' : 'transparent' }}>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
              <span style={{ font: '700 20px/1.1 var(--font-display)', color: 'var(--text-heading)' }}>{p.count}</span>
              <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{p.late ? `${p.late} late` : 'function'}</span>
            </div>
            {p.bottleneck && <span style={{ alignSelf: 'flex-start', font: '600 10px/16px var(--font-text)', padding: '0 6px', borderRadius: 6, background: 'var(--surface-inverse)', color: 'var(--text-inverse)' }}>Bottleneck</span>}
          </div>
        ))}
      </div>
    </section>
  );
}

function ToneSwatch({ tone, label }: { tone: string; label: string }) {
  return (
    <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
      <span style={{ width: 14, height: 14, borderRadius: 5, ...toneFill(tone) }} />{label}
    </span>
  );
}

/** A step not started yet has a dashed outline, whatever its tone; NYS on schedule has no fill, a late one keeps its tone's fill. */
function toneFill(tone: string, notStarted = tone === 'nys') {
  return notStarted
    ? { background: tone === 'nys' ? 'transparent' : `var(--tn-${tone}-bg)`, border: `1px dashed var(--tn-${tone})` }
    : { background: `var(--tn-${tone}-bg)`, border: `1px solid var(--tn-${tone})` };
}

function ClosedBadge() {
  return (
    <span title="Closed" style={{ display: 'inline-flex', width: 14, height: 14, borderRadius: '50%', alignItems: 'center', justifyContent: 'center', background: 'var(--tn-clo)', color: 'var(--tn-clo-bg)', flex: 'none' }}>
      <Icon name="check" size={10} />
    </span>
  );
}

function OverBadge() {
  return (
    <span title="Over estimate" style={{ display: 'inline-flex', width: 18, height: 18, borderRadius: 6, alignItems: 'center', justifyContent: 'center', background: 'var(--hl-amber)', color: 'var(--hl-amber-fg)' }}>
      <Icon name="trending-up" size={12} />
    </span>
  );
}

const FS: Record<string, [string, string]> = { done: ['Done', 'clo'], nys: ['NYS', 'nys'], delay: ['Delay', 'delay'], ontime: ['On schedule', 'ontime'] };

function FnRow({ r }: { r: Schemas['FunctionRow'] }) {
  const members = useMemberMap();
  const [fl, ft] = FS[r.state] ?? FS.ontime;
  const ba = av(members[r.ba_id ?? ''], r.ba_id ?? '–');
  const bal = av(members[r.bal_id ?? ''], r.bal_id ?? '–');
  const n = r.cells.length; // 3 or 5 steps
  const stepText = r.current >= n ? `All ${n} steps done` : `Step ${r.current + 1}/${n} · ${STEPS[r.current].label}`;
  return (
    <div style={{ display: 'grid', gridTemplateColumns: MX_GRID, minWidth: 1010, borderBottom: '1px solid var(--border-subtle)' }}>
      <div style={{ padding: '10px 14px', display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 4, minWidth: 0 }}>
        <span className="ellipsis" style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{r.name}</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--text-muted)' }}>
          <span title={`BA · ${ba.name}`} style={{ display: 'contents' }}><Avatar a={ba} size={18} fs={10} /><span className="heading">{ba.name}</span></span>
          <span style={{ width: 1, height: 12, background: 'var(--border-subtle)', margin: '0 2px' }} />
          <span title={`BAL · ${bal.name}`} style={{ display: 'contents' }}><Avatar a={bal} size={18} fs={10} /><span className="heading">{bal.name}</span></span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 2 }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, height: 20, padding: '0 8px', borderRadius: 999, background: `var(--tn-${ft}-bg)`, font: '600 11px/1 var(--font-text)', color: 'var(--text-heading)', whiteSpace: 'nowrap' }}>
            <Dot c={`var(--tn-${ft})`} size={7} />{fl}
          </span>
          <span title={stepText} className="ellipsis" style={{ flex: 1, minWidth: 0, fontSize: 11, color: 'var(--text-muted)' }}>{stepText}</span>
          <div title={r.cells.map((c, i) => `${STEPS[i].label}: ${c ? statusOf(c.status).label : '—'}`).join(' · ')} style={{ display: 'flex', gap: 2 }}>
            {r.cells.map((c, i) => <span key={i} style={{ width: 10, height: 6, borderRadius: 3, background: c ? `var(--tn-${c.tone})` : 'var(--border-subtle)' }} />)}
          </div>
        </div>
      </div>
      {r.cells.map((c, i) => (c ? <Cell key={c.key} c={c} /> : <div key={i} style={{ margin: 3, borderRadius: 10, border: '1px dashed var(--border-subtle)' }} title="Sub-task not in Jira" />))}
      {n < STEPS.length && (
        <div style={{ gridColumn: `${n + 2} / span ${STEPS.length - n}`, display: 'flex', alignItems: 'center', padding: '0 14px', fontSize: 11, color: 'var(--text-muted)' }}>No JM steps</div>
      )}
    </div>
  );
}

/**
 * A single ticket: a sub-task outside the 5-step workflow (study, QnA…), one row of its own even when others share its
 * name; its card sits in the first step column.
 */
function TicketRow({ t }: { t: Schemas['MatrixTicket'] }) {
  const members = useMemberMap();
  const a = av(members[t.card.assignee_id ?? ''], t.card.assignee_id ?? '–');
  return (
    <div style={{ display: 'grid', gridTemplateColumns: MX_GRID, minWidth: 1010, borderBottom: '1px solid var(--border-subtle)' }}>
      <div style={{ padding: '10px 14px', display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 4, minWidth: 0 }}>
        <span className="ellipsis" title={t.card.summary} style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{t.name}</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--text-muted)' }}>
          <Avatar a={a} size={18} fs={10} /><span className="heading">{a.name}</span>
          <span style={{ marginLeft: 4, font: '600 10px/16px var(--font-text)', padding: '0 6px', borderRadius: 999, background: 'var(--surface-hover)' }}>Single ticket</span>
        </div>
      </div>
      <Cell c={t.card} />
      <div style={{ gridColumn: '3 / span 4', display: 'flex', alignItems: 'center', padding: '0 14px', fontSize: 11, color: 'var(--text-muted)' }}>No review steps</div>
    </div>
  );
}

function Cell({ c }: { c: TaskCard }) {
  const members = useMemberMap();
  const open = useOpenTicket();
  const active = useTicketKey() === c.key;
  const a = av(members[c.assignee_id ?? ''], c.assignee_id ?? '–');
  const closed = c.status === 'C' || c.status === 'R';
  const act = c.actual_start ? (closed ? `${dm(c.actual_start)} – ${dm(c.actual_end)}` : `${dm(c.actual_start)} – `) : '–';
  const showHold = c.status !== 'C' && c.held_days > 0;
  const fix = c.step === 'fixb' || c.step === 'fixj';
  const tone = c.tone as Tone;
  return (
    <button className="hv-dim" onClick={() => open(c.key)} aria-label={`${c.key} ${statusOf(c.status).label}`} style={{
      position: 'relative', display: 'flex', flexDirection: 'column', gap: 5, padding: '8px 10px', minHeight: 68, margin: 3, boxSizing: 'border-box', borderRadius: 10,
      ...(c.status === 'N' ? toneFill(tone, true) : { border: 0, background: `var(--tn-${tone}-bg)` }), boxShadow: active ? 'inset 0 0 0 2px var(--border-focus)' : 'none', textAlign: 'left', cursor: 'pointer', font: 'inherit', color: 'inherit',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        {c.status === 'C' ? <ClosedBadge /> : <Dot c={`var(--tn-${tone})`} />}
        <span title={statusOf(c.status).label + (c.status === 'W' ? ' · ' + TONE[tone] : '') + (c.late ? ` · late ${c.late_days} days` : '')}
          style={{ font: '600 12px/1 var(--font-mono)', color: 'var(--text-heading)' }}>{c.key}</span>
        <span className="spacer" />
        {c.over && <OverBadge />}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <div title={`Original estimate ${hrs(c.estimate_h)} · Time spent ${hrs(c.logged_h)} · Remaining ${hrs(c.remaining_h)}`} style={{ display: 'flex', alignItems: 'baseline', gap: 5 }}>
          <span style={{ font: '600 10px/1 var(--font-text)', color: 'var(--text-muted)' }}>Remain</span>
          <span style={{ font: '600 13px/1 var(--font-mono)', color: c.over ? 'var(--hl-red-fg)' : 'var(--text-body)' }}>{f1(c.remaining_h)}h</span>
        </div>
        <span className="spacer" />
        <Avatar a={a} size={20} title={a.name} />
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '3px 6px', alignItems: 'center', font: '500 11px/1 var(--font-mono)' }}>
        <span style={{ font: '600 10px/1 var(--font-text)', color: 'var(--text-muted)' }}>Plan</span>
        <span title="Planned Start – Planned End" style={{ color: c.late ? 'var(--hl-red-fg)' : 'var(--text-muted)' }}>{dm(c.planned_start)} – {dm(c.planned_end)}</span>
        <span style={{ font: '600 10px/1 var(--font-text)', color: 'var(--text-muted)' }}>Act</span>
        <span title="Actual Start – Actual End" style={{ color: !c.actual_end ? 'var(--text-muted)' : c.schedule.finish === 'late_finish' ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{act}</span>
      </div>
      {((fix && c.rounds > 0) || showHold) && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, color: 'var(--text-body)' }}>
          {fix && c.rounds > 0 && (
            <span title="Review rounds" style={{ display: 'inline-flex', alignItems: 'center', gap: 3, fontWeight: 600, color: c.rounds >= 3 ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>
              <Icon name="refresh-cw" size={11} />{c.rounds}
            </span>
          )}
          {showHold && <span style={{ whiteSpace: 'nowrap' }}>with <b className="heading">{a.name}</b> · {c.held_days} days</span>}
        </div>
      )}
    </button>
  );
}
