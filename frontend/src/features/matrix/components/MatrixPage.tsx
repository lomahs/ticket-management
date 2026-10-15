import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { useState, type MouseEvent } from 'react';
import type { Schemas } from '../../../shared/api/types';
import { useMemberMap } from '../../../shared/api/queries';
import type { TaskCard, Tone } from '../../../shared/api/types';
import { Button, Icon, IconButton, Select, TextField } from '../../../shared/ds';
import { dm, f1, hrs } from '../../../shared/format/dates';
import { STEPS, TONE, statusOf } from '../../../shared/format/labels';
import { useOpenTicket, useTicketKey } from '../../../shared/state/ticket';
import { Avatar, av, Dot } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { matrixQuery } from '../api';
import {
  anyFilter, applyFilter, fromSearch, matchesText, pendingAtStep, stepRange, summaryCounts, toSearch, type MatrixFilter,
} from '../model/filters';
import { MatrixHeader } from './MatrixHeader';

const route = getRouteApi('/anken/$epic/matrix');
const SUM_GRID = '150px repeat(3,minmax(0,1fr)) 12px minmax(0,1fr) 80px';
const MX_GRID = '250px repeat(5,minmax(150px,1fr))';
const SCOLS: { k: string; kind: 'status' | 'prog'; label: string; dot: string; col: string; count: 'N' | 'W' | 'D' | 'late' }[] = [
  { k: 'N', kind: 'status', label: 'NYS', dot: 'var(--tn-nys)', col: '2', count: 'N' },
  { k: 'W', kind: 'status', label: 'WIP', dot: 'var(--tn-ontime)', col: '3', count: 'W' },
  { k: 'D', kind: 'status', label: 'Done', dot: 'var(--tn-clo)', col: '4', count: 'D' },
  { k: 'late', kind: 'prog', label: 'Late', dot: 'var(--tn-delay)', col: '6', count: 'late' },
];

export function MatrixPage() {
  const { epic } = route.useParams();
  const { task } = route.useSearch();
  const q = useQuery(matrixQuery(epic, task));
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, minWidth: 0 }}>
      <MatrixHeader epic={epic} title={q.data?.title} phase={q.data?.phase} tab="matrix" />
      {q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : <Matrix m={q.data} />}
    </div>
  );
}

function Matrix({ m }: { m: Schemas['MatrixView'] }) {
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const members = useMemberMap();
  const f = fromSearch(search);
  const setF = (patch: Partial<MatrixFilter>) => navigate({
    search: (p) => ({ ticket: (p as { ticket?: string }).ticket, task: (p as { task?: string }).task, ...toSearch({ ...f, ...patch }) }), replace: true,
  });
  const setTask = (task: string) => navigate({ search: (p) => ({ ticket: (p as { ticket?: string }).ticket, task }) });
  const [sumOpen, setSumOpen] = useState(true);
  const [rangeOpen, setRangeOpen] = useState<{ i: number; top: number; left: number } | null>(null);

  const rows = applyFilter(m.functions, f);
  const pool = m.functions.filter((r) => matchesText(r, f.q));
  const ids = Object.keys(members);
  const bas = ids.filter((id) => members[id].role === 'BA' && pool.some((r) => r.owner_id === id));
  const bals = ids.filter((id) => members[id].role === 'BAL' && pool.some((r) => r.reviewer_id === id));
  const summary = summaryCounts(pool, bas, bals);
  const toggle = (list: string[], v: string) => (list.includes(v) ? list.filter((x) => x !== v) : [...list, v]);
  const s = m.stats;
  const stats = [
    { label: 'Progress', value: s.progress_pct + '%', sub: `${s.functions_done}/${s.functions_total} functions done` },
    { label: 'Late functions', value: s.late_functions, sub: `${s.late_subtasks} sub-task`, color: 'var(--hl-red-fg)' },
    { label: 'Over estimate', value: s.over_subtasks, sub: 'sub-task' },
    { label: 'Effort', value: hrs(s.logged_h), sub: `/ ${hrs(s.estimate_h)} estimate` },
  ];
  const pending = pendingAtStep(rows);
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

  if (!m.functions.length) {
    return <div className="card" style={{ padding: 24, color: 'var(--text-muted)' }}>No sub-tasks in this anken follow the naming rule yet. Check Settings → Name rules.</div>;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {m.parents.length > 1 && (
        <Select label="Task" style={{ maxWidth: 480 }} value={m.task_key ?? ''} onChange={setTask}
          options={m.parents.map((p) => ({ value: p.key, label: `${p.summary} · ${p.functions} function${p.functions === 1 ? '' : 's'}` }))} />
      )}
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
            <span style={{ fontWeight: 700, color: 'var(--text-heading)' }}>WIP</span>
            {(['ontime', 'early', 'latestart', 'delay'] as const).map((k) => <ToneSwatch key={k} tone={k} label={TONE[k]} />)}
          </span>
          <ToneSwatch tone="res" label="Resolved" />
          <ToneSwatch tone="clo" label="Closed" />
        </div>
        <span className="spacer" />
        <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}><OverBadge />Over estimate</span>
      </div>

      <div className="card" style={{ overflowX: 'auto' }}>
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

        {rows.map((r) => <FnRow key={r.id} r={r} />)}

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
      </div>
      {!rows.length && <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-muted)' }}>No functions match the filters.</div>}

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

function ToneSwatch({ tone, label }: { tone: string; label: string }) {
  return (
    <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
      <span style={{ width: 14, height: 14, borderRadius: 5, background: `var(--tn-${tone}-bg)`, border: `1px solid var(--tn-${tone})` }} />{label}
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
  const ba = av(members[r.owner_id ?? ''], r.owner_id ?? '–');
  const bal = av(members[r.reviewer_id ?? ''], r.reviewer_id ?? '–');
  const stepText = r.current >= 5 ? 'All 5 steps done' : `Step ${r.current + 1}/5 · ${STEPS[r.current].label}`;
  return (
    <div style={{ display: 'grid', gridTemplateColumns: MX_GRID, minWidth: 1010, borderBottom: '1px solid var(--border-subtle)' }}>
      <div style={{ padding: '10px 14px', display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 4, minWidth: 0 }}>
        <span className="ellipsis" style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{r.name}</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--text-muted)' }}>
          <Avatar a={ba} size={18} fs={10} /><span className="heading">{ba.name}</span>
          <span style={{ width: 1, height: 12, background: 'var(--border-subtle)', margin: '0 2px' }} />
          <Avatar a={bal} size={18} fs={10} /><span className="heading">{bal.name}</span>
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
      position: 'relative', display: 'flex', flexDirection: 'column', gap: 5, padding: '8px 10px', minHeight: 68, margin: 3, border: 0, borderRadius: 10,
      background: `var(--tn-${tone}-bg)`, boxShadow: active ? 'inset 0 0 0 2px var(--border-focus)' : 'none', textAlign: 'left', cursor: 'pointer', font: 'inherit', color: 'inherit',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <Dot c={`var(--tn-${tone})`} />
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
        <span title="Actual Start – Actual End" style={{ color: !c.actual_end ? 'var(--text-muted)' : c.planned_end && c.actual_end > c.planned_end && closed ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{act}</span>
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
