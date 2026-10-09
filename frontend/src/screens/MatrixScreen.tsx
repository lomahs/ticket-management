import { useEffect, useState, type MouseEvent } from 'react';
import { api } from '../api/client';
import type { Fn, Snapshot, Task } from '../api/types';
import { Avatar, Dot } from '../components/bits';
import { Button, Icon, IconButton, Tabs, TextField } from '../ds';
import { dm, f1, relTime, wd } from '../lib/dates';
import { av, fState, grpStatus, logSpan, pct, prog, STAT, TONE_L } from '../lib/domain';
import { useApp } from '../state/AppContext';
import { AddFunction } from './AddFunction';

type FilterKey = 'fStatus' | 'fProg';
interface Range { from?: string; to?: string }

const SUM_GRID = '150px repeat(3,minmax(0,1fr)) 12px minmax(0,1fr) 80px';
const MX_GRID = '250px repeat(5,minmax(150px,1fr))';

const SCOLS: { k: string; t: FilterKey; label: string; dot: string; test: (f: Fn, today: string) => boolean }[] = [
  { k: 'N', t: 'fStatus', label: 'NYS', dot: 'var(--tn-nys)', test: (f) => grpStatus(f) === 'N' },
  { k: 'W', t: 'fStatus', label: 'WIP', dot: 'var(--tn-ontime)', test: (f) => grpStatus(f) === 'W' },
  { k: 'D', t: 'fStatus', label: 'Done', dot: 'var(--tn-clo)', test: (f) => grpStatus(f) === 'D' },
  { k: 'late', t: 'fProg', label: 'Late', dot: 'var(--tn-delay)', test: (f, today) => prog(f, today) === 'late' },
];
const colOf = (i: number) => (i < 3 ? String(i + 2) : '6');

function useTick(ms: number) {
  const [, set] = useState(0);
  useEffect(() => {
    const t = setInterval(() => set((n) => n + 1), ms);
    return () => clearInterval(t);
  }, [ms]);
}

function SyncPill() {
  const { snap, pending, write } = useApp();
  const [syncing, setSyncing] = useState(false);
  useTick(30000);
  const busy = syncing || pending > 0;
  const refresh = async () => {
    setSyncing(true);
    const minSpin = new Promise((r) => setTimeout(r, 600));
    await Promise.all([write(api.sync()), minSpin]);
    setSyncing(false);
  };
  return (
    <div style={{
      display: 'inline-flex', alignItems: 'center', gap: 6, height: 28, padding: '0 4px 0 10px', borderRadius: 999, border: '1px solid var(--border-subtle)',
      background: 'var(--surface-card)', fontSize: 12, color: 'var(--text-muted)', whiteSpace: 'nowrap',
    }}>
      <Dot c={busy ? 'var(--st-wip)' : 'var(--st-clo)'} />
      <span>{pending ? `Writing ${pending} changes to Jira…` : syncing ? 'Syncing…' : 'Synced with Jira · ' + relTime(snap.lastSync)}</span>
      <button className="hv-bg" onClick={refresh} title="Sync again from Jira" aria-label="Sync again from Jira" style={{
        display: 'flex', alignItems: 'center', justifyContent: 'center', width: 24, height: 24, padding: 0, border: 0, borderRadius: '50%',
        background: 'transparent', color: 'var(--text-heading)', cursor: 'pointer',
      }}>
        <span style={{ display: 'flex', lineHeight: 0, animation: syncing ? 'ttspin 0.9s linear infinite' : 'none' }}><Icon name="refresh-cw" size={13} /></span>
      </button>
    </div>
  );
}

export function MatrixScreen() {
  const { snap } = useApp();
  const [tab, setTab] = useState<'matrix' | 'add'>('matrix');
  const anken = snap.anken.find((a) => a.matrix);
  const parent = snap.parents[0];
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, minWidth: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <div style={{ flex: '1 1 260px', minWidth: 0 }}>
          <div className="eyebrow">{parent?.key} · {anken?.name} · Implement_Coding</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap', marginTop: 2 }}>
            <h1 className="h1">Function Matrix</h1>
            <SyncPill />
          </div>
        </div>
      </div>
      <Tabs tabs={[{ value: 'matrix', label: 'Matrix' }, { value: 'add', label: 'Add function' }]} value={tab} onChange={setTab} />
      {tab === 'add' ? <AddFunction onDone={() => setTab('matrix')} /> : <Matrix snap={snap} />}
    </div>
  );
}

function Matrix({ snap }: { snap: Snapshot }) {
  const { ticket, openTicket } = useApp();
  const today = snap.today;
  const fns = snap.functions;
  const [sel, setSel] = useState<string[]>([]);
  const [flt, setFlt] = useState<Record<FilterKey, string[]>>({ fStatus: [], fProg: [] });
  const [q, setQ] = useState('');
  const [ranges, setRanges] = useState<Record<number, Range>>({});
  const [sort, setSort] = useState<{ i: number; dir: 1 | -1 } | null>(null);
  const [sumOpen, setSumOpen] = useState(true);
  const [rangeOpen, setRangeOpen] = useState<{ i: number; top: number; left: number } | null>(null);

  const ql = q.trim().toLowerCase();
  const mOk = (f: Fn) => !sel.length || sel.includes(f.ba) || sel.includes(f.cells[1].assignee);
  const sOk = (f: Fn) => !flt.fStatus.length || flt.fStatus.includes(grpStatus(f));
  const pOk = (f: Fn) => !flt.fProg.length || flt.fProg.includes(prog(f, today));
  const qOk = (f: Fn) => !ql || (f.id + ' ' + f.name).toLowerCase().includes(ql);
  const rOk = (f: Fn) => Object.entries(ranges).every(([i, r]) => {
    const c = f.cells[Number(i)];
    return (!r.from || c.pe >= r.from) && (!r.to || c.ps <= r.to);
  });
  let rows = fns.filter((f) => mOk(f) && sOk(f) && pOk(f) && qOk(f) && rOk(f));
  if (sort) rows = [...rows].sort((a, b) => (a.cells[sort.i].ps < b.cells[sort.i].ps ? -1 : a.cells[sort.i].ps > b.cells[sort.i].ps ? 1 : 0) * sort.dir);

  const tog = (k: FilterKey, v: string) => setFlt((s) => ({ ...s, [k]: s[k].includes(v) ? s[k].filter((x) => x !== v) : [...s[k], v] }));
  const togSel = (id: string) => setSel((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));
  const anyFilter = !!(sel.length || flt.fStatus.length || flt.fProg.length || ql || Object.keys(ranges).length);
  const clearAll = () => { setSel([]); setFlt({ fStatus: [], fProg: [] }); setQ(''); setRanges({}); };

  // Stats strip
  const all = fns.flatMap((f) => f.cells);
  const tl = all.reduce((a, c) => a + c.logged, 0), te = all.reduce((a, c) => a + c.est, 0);
  const avgP = fns.length ? Math.round(fns.reduce((a, f) => a + pct(f), 0) / fns.length) : 0;
  const stats = [
    { label: 'Progress', value: avgP + '%', sub: `${fns.filter((f) => f.cur === 5).length}/${fns.length} functions done` },
    { label: 'Late functions', value: fns.filter((f) => f.cells.some((c) => c.late)).length, sub: `${all.filter((c) => c.late).length} sub-task`, color: 'var(--hl-red-fg)' },
    { label: 'Over estimate', value: all.filter((c) => c.over).length, sub: 'sub-task' },
    { label: 'Effort', value: f1(tl) + 'h', sub: `/ ${f1(te)}h estimate` },
  ];

  // Status summary
  const pool = fns.filter(qOk);
  const ids = Object.keys(snap.members);
  const sumRows = [
    ...ids.filter((id) => snap.members[id].role === 'BA').map((id) => ({ id, role: 'BA', list: pool.filter((f) => f.ba === id), sep: false })),
    ...ids.filter((id) => snap.members[id].role === 'BAL').map((id, i) => ({ id, role: 'BAL', list: pool.filter((f) => f.cells[1].assignee === id), sep: i === 0 })),
    { id: null as string | null, role: '', list: pool, sep: true },
  ];

  // Step column headers
  const stepHeads = snap.steps.map((st, i) => {
    const cs = rows.map((f) => f.cells[i]);
    const mn = cs.reduce((a, c) => (!a || c.ps < a ? c.ps : a), '');
    const mx = cs.reduce((a, c) => (c.pe > a ? c.pe : a), '');
    const rr = ranges[i];
    const hasR = !!(rr && (rr.from || rr.to));
    const sd = sort?.i === i ? sort.dir : 0;
    return {
      label: st.label, hasR, sd,
      range: hasR ? (rr.from ? dm(rr.from) : '…') + ' – ' + (rr.to ? dm(rr.to) : '…') : cs.length ? dm(mn) + ' – ' + dm(mx) : '–',
    };
  });
  const setRange = (i: number, k: keyof Range, v: string) => setRanges((s) => {
    const r = { ...(s[i] || {}), [k]: v };
    const n = { ...s, [i]: r };
    if (!r.from && !r.to) delete n[i];
    return n;
  });
  const openRange = (i: number, e: MouseEvent<HTMLButtonElement>) => {
    const b = e.currentTarget.getBoundingClientRect();
    setRangeOpen((cur) => (cur?.i === i ? null : { i, top: b.bottom + 6, left: Math.max(8, Math.min(b.left, innerWidth - 370)) }));
  };

  const counts = snap.steps.map((_, i) => rows.filter((f) => f.cur === i));
  const mxCount = Math.max(...counts.map((l) => l.length));

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div className="card" style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'stretch', overflow: 'hidden' }}>
        {stats.map((s, i) => (
          <div key={s.label} style={{ flex: '1 1 140px', padding: '10px 16px', borderLeft: i ? '1px solid var(--border-subtle)' : 0 }}>
            <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{s.label}</div>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
              <span style={{ font: '700 20px/1.3 var(--font-display)', color: s.color ?? 'var(--text-heading)' }}>{s.value}</span>
              <span className="eyebrow">{s.sub}</span>
            </div>
          </div>
        ))}
      </div>

      <section className="card" style={{ display: 'flex', flexDirection: 'column', gap: 10, padding: '14px 16px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <IconButton icon={sumOpen ? 'chevron-up' : 'chevron-down'} label={sumOpen ? 'Hide summary' : 'Show summary'} size="sm" onClick={() => setSumOpen(!sumOpen)} style={{ marginLeft: -6 }} />
          <span className="title">Status summary</span>
          <span className="eyebrow">Functions by current step · click a column, member or count to filter</span>
          <span className="spacer" />
          {anyFilter && <Button appearance="subtle" size="sm" iconBefore="x" onClick={clearAll}>Clear filters</Button>}
          <span className="eyebrow">{rows.length}/{fns.length} function</span>
        </div>
        {sumOpen && (
          <div style={{ overflowX: 'auto' }}>
            <div style={{ minWidth: 560, display: 'flex', flexDirection: 'column', gap: 2 }}>
              <div style={{ display: 'grid', gridTemplateColumns: SUM_GRID, gap: 2, alignItems: 'stretch' }}>
                <span style={{ padding: '6px 10px', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)' }}>Member</span>
                {SCOLS.map((c, i) => {
                  const on = flt[c.t].includes(c.k);
                  return (
                    <button key={c.k} className="hv-bg" onClick={() => tog(c.t, c.k)} style={{
                      display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6, height: 30, padding: '0 6px', border: 0, borderRadius: 999,
                      background: on ? 'var(--surface-tint)' : 'transparent', boxShadow: on ? 'inset 0 0 0 1px var(--border-focus)' : 'none',
                      color: 'var(--text-heading)', font: '600 11px/1 var(--font-text)', cursor: 'pointer', gridColumn: colOf(i),
                    }}>
                      <Dot c={c.dot} />{c.label}
                    </button>
                  );
                })}
                <span style={{ gridColumn: 7, padding: '6px 10px', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)', textAlign: 'center' }}>Total</span>
              </div>
              {sumRows.map((r) => {
                const m = r.id ? snap.members[r.id] : null;
                const rowOn = !!r.id && sel.includes(r.id);
                return (
                  <div key={r.id ?? 'all'} style={{ display: 'grid', gridTemplateColumns: SUM_GRID, gap: 2, alignItems: 'stretch', borderTop: r.id && !r.sep ? 0 : '1px solid var(--border-subtle)' }}>
                    <button className="hv-bg" onClick={() => (r.id ? togSel(r.id) : setSel([]))} style={{
                      display: 'flex', alignItems: 'center', gap: 8, height: 34, padding: '0 10px', border: 0, borderRadius: 10,
                      background: rowOn ? 'var(--surface-tint)' : 'transparent', color: 'var(--text-heading)',
                      font: `${rowOn || !r.id ? 700 : 500} 12px/1 var(--font-text)`, cursor: 'pointer', textAlign: 'left',
                    }}>
                      <Avatar a={{ ini: m ? m.name[0] : 'Σ', color: m ? m.color : 'var(--text-muted)' }} size={20} />
                      {m ? m.name : 'All'}
                      {r.role && <span style={{ font: '600 10px/16px var(--font-text)', padding: '0 6px', borderRadius: 999, background: 'var(--surface-hover)', color: 'var(--text-muted)' }}>{r.role}</span>}
                    </button>
                    {SCOLS.map((c, i) => {
                      const n = r.list.filter((f) => c.test(f, today)).length;
                      const on = flt[c.t].includes(c.k) && (r.id ? sel.includes(r.id) : !sel.length);
                      const hot = c.k === 'late' && n > 0;
                      const pick = () => {
                        const same = on && flt[c.t].length === 1;
                        setSel(same || !r.id ? [] : [r.id]);
                        setFlt(same ? { fStatus: [], fProg: [] } : { fStatus: [], fProg: [], [c.t]: [c.k] });
                      };
                      return (
                        <button key={c.k} className="hv-ring" onClick={pick} style={{
                          height: 34, border: 0, borderRadius: 10, cursor: 'pointer', gridColumn: colOf(i),
                          background: on ? 'var(--surface-tint)' : hot ? 'var(--hl-red)' : 'var(--surface-hover)',
                          boxShadow: on ? 'inset 0 0 0 2px var(--border-focus)' : 'none',
                          color: n ? (hot ? 'var(--hl-red-fg)' : 'var(--text-heading)') : 'var(--text-disabled)',
                          font: `${n ? 700 : 400} 13px/1 var(--font-mono)`,
                        }}>{n || '–'}</button>
                      );
                    })}
                    <span style={{ gridColumn: 7, display: 'flex', alignItems: 'center', justifyContent: 'center', font: '700 13px/1 var(--font-mono)', color: 'var(--text-heading)' }}>{r.list.length}</span>
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
            {(['ontime', 'early', 'latestart', 'delay'] as const).map((k) => <ToneSwatch key={k} tone={k} label={TONE_L[k]} gap={5} />)}
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
            <TextField size="sm" iconBefore="search" placeholder="Search functions…" value={q} onChange={setQ} aria-label="Search functions" />
          </div>
          {stepHeads.map((h, i) => (
            <div key={h.label} style={{ position: 'relative', padding: '8px 12px', borderLeft: '1px solid var(--border-subtle)', display: 'flex', flexDirection: 'column', gap: 6 }}>
              <button onClick={() => setSort(h.sd === 0 ? { i, dir: 1 } : h.sd === 1 ? { i, dir: -1 } : null)} title="Sort by Planned Start" style={{
                display: 'flex', alignItems: 'center', gap: 6, alignSelf: 'flex-start', height: 19, padding: 0, border: 0, background: 'transparent',
                color: 'var(--text-heading)', font: '600 13px/19px var(--font-text)', cursor: 'pointer', whiteSpace: 'nowrap',
              }}>
                {h.label}
                <span style={{ display: 'flex', color: h.sd ? 'var(--text-heading)' : 'var(--text-muted)' }}>
                  <Icon name={h.sd === 1 ? 'arrow-up' : h.sd === -1 ? 'arrow-down' : 'arrow-up-down'} size={13} />
                </span>
              </button>
              <button className="hv-bd" onClick={(e) => openRange(i, e)} title="Filter by Planned Start – End range" style={{
                display: 'flex', alignItems: 'center', gap: 6, height: 30, padding: '0 10px', borderRadius: 999,
                border: `1px solid ${h.hasR ? 'var(--border-focus)' : 'var(--border-strong)'}`, background: h.hasR ? 'var(--surface-tint)' : 'var(--surface-card)',
                color: 'var(--text-heading)', font: '500 11px/1 var(--font-mono)', cursor: 'pointer', whiteSpace: 'nowrap',
              }}>
                <Icon name="calendar" size={12} />
                <span style={{ flex: 1, textAlign: 'left' }}>{h.range}</span>
                <Icon name="chevron-down" size={12} />
              </button>
            </div>
          ))}
        </div>

        {rows.map((f) => <FnRow key={f.id} f={f} snap={snap} ticket={ticket} open={openTicket} />)}

        <div style={{ display: 'grid', gridTemplateColumns: MX_GRID, minWidth: 1010, background: 'var(--surface-hover)' }}>
          <div style={{ padding: '10px 14px' }}>
            <div style={{ fontWeight: 600, color: 'var(--text-heading)' }}>Pending at step</div>
            <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>Functions whose current step is this column</div>
          </div>
          {counts.map((l, i) => {
            const lateN = l.filter((f) => f.cells[f.cur].late).length;
            const isMax = l.length === mxCount && mxCount > 0;
            return (
              <div key={i} style={{ margin: '4px 3px', padding: '8px 10px', borderRadius: 10, display: 'flex', flexDirection: 'column', gap: 4, background: isMax ? 'var(--surface-tint)' : 'transparent' }}>
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
                  <span style={{ font: '700 20px/1.1 var(--font-display)', color: 'var(--text-heading)' }}>{l.length}</span>
                  <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{lateN ? `${lateN} late` : 'function'}</span>
                </div>
                {isMax && <span style={{ alignSelf: 'flex-start', font: '600 10px/16px var(--font-text)', padding: '0 6px', borderRadius: 6, background: 'var(--surface-inverse)', color: 'var(--text-inverse)' }}>Bottleneck</span>}
              </div>
            );
          })}
        </div>
      </div>
      {!rows.length && <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-muted)' }}>No functions match the filters.</div>}

      {rangeOpen && (
        <>
          <div onClick={() => setRangeOpen(null)} style={{ position: 'fixed', inset: 0, zIndex: 55 }} />
          <div style={{
            position: 'fixed', top: rangeOpen.top, left: rangeOpen.left, zIndex: 56, padding: 12, display: 'flex', flexDirection: 'column', gap: 10,
            borderRadius: 14, background: 'var(--surface-card)', boxShadow: 'var(--shadow-overlay)',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <TextField type="date" value={ranges[rangeOpen.i]?.from ?? ''} onChange={(v) => setRange(rangeOpen.i, 'from', v)} style={{ width: 150 }} aria-label="From" />
              <span className="muted">–</span>
              <TextField type="date" value={ranges[rangeOpen.i]?.to ?? ''} onChange={(v) => setRange(rangeOpen.i, 'to', v)} style={{ width: 150 }} aria-label="To" />
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 6 }}>
              <Button appearance="subtle" size="sm" onClick={() => { setRanges((s) => { const n = { ...s }; delete n[rangeOpen.i]; return n; }); setRangeOpen(null); }}>Clear</Button>
              <Button size="sm" onClick={() => setRangeOpen(null)}>Done</Button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function ToneSwatch({ tone, label, gap = 6 }: { tone: string; label: string; gap?: number }) {
  return (
    <span style={{ display: 'flex', alignItems: 'center', gap }}>
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

const FS = { done: ['Done', 'clo'], nys: ['NYS', 'nys'], delay: ['Delay', 'delay'], ontime: ['On schedule', 'ontime'] } as const;

function FnRow({ f, snap, ticket, open }: { f: Fn; snap: Snapshot; ticket: string | null; open: (k: string) => void }) {
  const [fl, ft] = FS[fState(f)];
  const ba = av(snap, f.ba), bal = av(snap, f.cells[1].assignee);
  const stepLabel = f.cur === 5 ? 'All 5 steps done' : `Step ${f.cur + 1}/5 · ${snap.steps[f.cur].label}`;
  return (
    <div style={{ display: 'grid', gridTemplateColumns: MX_GRID, minWidth: 1010, borderBottom: '1px solid var(--border-subtle)' }}>
      <div style={{ padding: '10px 14px', display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 4, minWidth: 0 }}>
        <span className="ellipsis" style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{f.name}</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--text-muted)' }}>
          <Avatar a={ba} size={18} fs={10} /><span className="heading">{ba.name}</span>
          <span style={{ width: 1, height: 12, background: 'var(--border-subtle)', margin: '0 2px' }} />
          <Avatar a={bal} size={18} fs={10} /><span className="heading">{bal.name}</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 2 }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, height: 20, padding: '0 8px', borderRadius: 999, background: `var(--tn-${ft}-bg)`, font: '600 11px/1 var(--font-text)', color: 'var(--text-heading)', whiteSpace: 'nowrap' }}>
            <Dot c={`var(--tn-${ft})`} size={7} />{fl}
          </span>
          <span title={stepLabel} className="ellipsis" style={{ flex: 1, minWidth: 0, fontSize: 11, color: 'var(--text-muted)' }}>{stepLabel}</span>
          <div title={f.cells.map((c) => c.stepLabel + ': ' + STAT[c.status].label).join(' · ')} style={{ display: 'flex', gap: 2 }}>
            {f.cells.map((c) => <span key={c.key} style={{ width: 10, height: 6, borderRadius: 3, background: `var(--tn-${c.tone})` }} />)}
          </div>
        </div>
      </div>
      {f.cells.map((c) => <Cell key={c.key} c={c} snap={snap} active={c.key === ticket} open={() => open(c.key)} />)}
    </div>
  );
}

function Cell({ c, snap, active, open }: { c: Task; snap: Snapshot; active: boolean; open: () => void }) {
  const a = av(snap, c.assignee);
  const rem = c.status === 'C' ? 0 : Math.max(0, c.est - c.logged);
  const ls = logSpan(c);
  const closed = c.status === 'C' || c.status === 'R';
  const act = ls ? (closed ? dm(ls[0]) + ' – ' + dm(ls[1]) : dm(ls[0]) + ' – ') : '–';
  const showHold = c.status !== 'C' && c.held > 0;
  const hasRounds = !!c.isFix && c.rounds > 0;
  const lateTip = c.late ? ` · Late by ${c.lateDays} days (plan ${dm(c.pe)}, ${wd(c.pe, snap.today)} working days)` : '';
  return (
    <button className="hv-dim" onClick={open} style={{
      position: 'relative', display: 'flex', flexDirection: 'column', gap: 5, padding: '8px 10px', minHeight: 68, margin: 3, border: 0, borderRadius: 10,
      background: `var(--tn-${c.tone}-bg)`, boxShadow: active ? 'inset 0 0 0 2px var(--border-focus)' : 'none', textAlign: 'left', cursor: 'pointer', font: 'inherit', color: 'inherit',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <Dot c={`var(--tn-${c.tone})`} />
        <span title={STAT[c.status].label + (c.status === 'W' ? ' · ' + TONE_L[c.tone] : '') + lateTip} style={{ font: '600 12px/1 var(--font-mono)', color: 'var(--text-heading)' }}>{c.key}</span>
        <span className="spacer" />
        {c.over && <OverBadge />}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <div title={`Original estimate ${f1(c.est)}h · Time spent ${f1(c.logged)}h · Remaining ${f1(rem)}h`} style={{ display: 'flex', alignItems: 'baseline', gap: 5 }}>
          <span style={{ font: '600 10px/1 var(--font-text)', color: 'var(--text-muted)' }}>Remain</span>
          <span style={{ font: '600 13px/1 var(--font-mono)', color: c.over ? 'var(--hl-red-fg)' : 'var(--text-body)' }}>{f1(rem)}h</span>
        </div>
        <span className="spacer" />
        <Avatar a={a} size={20} title={a.name} />
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '3px 6px', alignItems: 'center', font: '500 11px/1 var(--font-mono)' }}>
        <span style={{ font: '600 10px/1 var(--font-text)', color: 'var(--text-muted)' }}>Plan</span>
        <span title="Planned Start – Planned End" style={{ color: c.late ? 'var(--hl-red-fg)' : 'var(--text-muted)' }}>{dm(c.ps)} – {dm(c.pe)}</span>
        <span style={{ font: '600 10px/1 var(--font-text)', color: 'var(--text-muted)' }}>Act</span>
        <span title="Actual Start – Actual End" style={{ color: !ls ? 'var(--text-muted)' : ls[1] > c.pe ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{act}</span>
      </div>
      {(hasRounds || showHold) && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, color: 'var(--text-body)' }}>
          {hasRounds && (
            <span title="Rounds" style={{ display: 'inline-flex', alignItems: 'center', gap: 3, fontWeight: 600, color: c.rounds >= snap.config.rounds ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>
              <Icon name="refresh-cw" size={11} />{c.rounds}
            </span>
          )}
          {showHold && <span style={{ whiteSpace: 'nowrap' }}>with <b className="heading">{a.name}</b> · {c.held} days</span>}
        </div>
      )}
    </button>
  );
}
