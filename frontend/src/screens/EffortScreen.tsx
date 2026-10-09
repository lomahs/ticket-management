import { useMemo, useState, type MouseEvent } from 'react';
import type { Snapshot, Task } from '../api/types';
import { Avatar, Legend, Seg } from '../components/bits';
import { IconButton, Tabs } from '../ds';
import { add, dm, dow, f1, monLabel, monthDays, mondayOf, shiftMon, WDN } from '../lib/dates';
import { allCells, av, dayHead, extras, findTask, HT, planItems, title, type Cal } from '../lib/domain';
import { useApp } from '../state/AppContext';

type PlanDay = Record<string, Record<string, { t: number; k: Record<string, number> }>>;

/** Planned hours per member/day: each item's estimate spread evenly over its working days. */
function planDay(snap: Snapshot, cal: Cal): PlanDay {
  const P: PlanDay = {};
  for (const c of planItems(snap)) {
    if (!c.ps || !c.pe || !c.est) continue;
    const ds: string[] = [];
    for (let s = c.ps; s <= c.pe; s = add(s, 1)) if (!cal.off(s, c.worker)) ds.push(s);
    if (!ds.length) continue;
    const per = c.est / ds.length;
    for (const s of ds) {
      const d = ((P[c.worker] ??= {})[s] ??= { t: 0, k: {} });
      d.t += per;
      d.k[c.key] = (d.k[c.key] || 0) + per;
    }
  }
  return P;
}

interface PopPos { who: string; date: string; top: number; left: number }

export function EffortScreen() {
  const [tab, setTab] = useState<'ts' | 'an'>('ts');
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, width: '100%' }}>
      <div>
        <div className="eyebrow">Worklogs synced from Jira · all projects</div>
        <h1 className="h1" style={{ marginTop: 2 }}>Effort</h1>
      </div>
      <Tabs tabs={[{ value: 'ts', label: 'Timesheet' }, { value: 'an', label: 'Analysis' }]} value={tab} onChange={setTab} />
      {tab === 'ts' ? <Timesheet /> : <Analysis />}
    </div>
  );
}

function Timesheet() {
  const { snap, cal, openTicket } = useApp();
  const { today, members, config, timesheet: ts, leaveTypes, extraDays } = snap;
  const [view, setView] = useState<'week' | 'month'>('week');
  const [week, setWeek] = useState(() => mondayOf(today));
  const [mon, setMon] = useState(today.slice(0, 7));
  const [hov, setHov] = useState<PopPos | null>(null);
  const [pin, setPin] = useState<PopPos | null>(null);
  const PL = useMemo(() => planDay(snap, cal), [snap, cal]);

  const isW = view === 'week';
  const wk = [...Array(7)].map((_, i) => add(week, i));
  const days = (isW ? wk : monthDays(mon)).filter(cal.vis);
  const PER = 10;
  const fit = isW || days.length <= PER;
  const sum = (id: string, s: string) => (ts[id]?.[s] ?? []).reduce((a, e) => a + e.h, 0);
  const pos = (ev: MouseEvent<HTMLElement>, who: string, date: string): PopPos => {
    const r = ev.currentTarget.getBoundingClientRect();
    return { who, date, top: Math.max(8, Math.min(r.bottom + 6, innerHeight - 360)), left: Math.max(8, Math.min(r.left + r.width / 2 - 180, innerWidth - 376)) };
  };

  const rows = Object.keys(members).map((id) => {
    let tot = 0, exp = 0, miss = 0;
    const cells = days.map((s) => {
      const o = cal.off(s, id);
      const lv = cal.lv(id, s);
      const e = o ? 0 : config.hours * (lv && leaveTypes[lv.type].half ? 0.5 : 1);
      const fut = s > today, td = s === today;
      const h = sum(id, s);
      tot += h;
      if (!fut && !td) exp += e;
      const ph = PL[id]?.[s]?.t ?? 0;
      let bg = 'transparent', fg = 'var(--text-heading)', v = h ? f1(h) + 'h' : '';
      if (o) { bg = o === 'we' ? 'var(--surface-hover)' : HT('var(--border-subtle)') + ',var(--surface-hover)'; fg = 'var(--text-disabled)'; v = o === 'we' ? '' : 'off'; }
      else if (fut) v = '';
      else if (!h && !ph) { /* nothing planned or logged */ }
      else if (h > ph + 0.05) { bg = 'var(--hl-red)'; fg = 'var(--hl-red-fg)'; miss++; }
      else bg = 'var(--hl-ok)';
      return {
        s, v, bg, fg, can: !fut && (h > 0 || !o),
        pv: ph ? 'plan ' + f1(ph) + 'h' : o ? '' : 'plan –',
        tip: `${members[id].name} · ${WDN[dow(s)]} ${dm(s)} · ${f1(h)}h logged` + (o && o !== 'we' ? ' · ' + o : ''),
      };
    });
    return { id, m: members[id], cells, tot, exp, miss };
  });

  const pc = pin || hov;
  let pop: null | { title: string; total: string; exp: string; items: { key: string; title: string; h: string; plan: string; note: string }[]; empty: boolean } = null;
  if (pc) {
    const ents = ts[pc.who]?.[pc.date] ?? [];
    const g: Record<string, { key: string; title: string; h: number; notes: string[] }> = {};
    for (const e of ents) {
      const k = e.key || '_';
      (g[k] ??= { key: e.key, title: e.title, h: 0, notes: [] }).h += e.h;
      g[k].notes.push(e.note);
    }
    const pdd = PL[pc.who]?.[pc.date] ?? { t: 0, k: {} };
    for (const k of Object.keys(pdd.k)) {
      const t = findTask(snap, k);
      g[k] ??= { key: k, title: t ? title(t) : k, h: 0, notes: [] };
    }
    pop = {
      title: `${members[pc.who].name} · ${WDN[dow(pc.date)]} ${dm(pc.date)}`,
      total: f1(sum(pc.who, pc.date)) + 'h actual', exp: '· ' + f1(pdd.t) + 'h plan',
      empty: !ents.length && !Object.keys(pdd.k).length,
      items: Object.values(g).map((x) => ({
        key: x.key || '—', title: x.title, h: f1(x.h) + 'h', plan: 'plan ' + (pdd.k[x.key] ? f1(pdd.k[x.key]) + 'h' : '–'),
        note: [...new Set(x.notes)].join(' · ') || 'No worklog yet',
      })),
    };
  }

  const ti = days.indexOf(today);
  const prev = () => { setPin(null); setHov(null); if (isW) setWeek(add(week, -7)); else setMon(shiftMon(mon, -1)); };
  const next = () => { setPin(null); setHov(null); if (isW) setWeek(add(week, 7)); else setMon(shiftMon(mon, 1)); };

  return (
    <section className="card card-clip">
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)' }}>
        <div style={{ marginRight: 6 }}>
          <Seg items={[['week', 'Week'], ['month', 'Month']]} value={view} onPick={(k) => { setPin(null); setHov(null); if (k === 'month') setMon(week.slice(0, 7)); setView(k); }} />
        </div>
        <IconButton icon="chevron-left" label="Previous" appearance="secondary" size="sm" onClick={prev} />
        <span style={{ fontWeight: 700, color: 'var(--text-heading)', minWidth: 120, textAlign: 'center' }}>
          {isW ? dm(wk[0]) + ' – ' + dm(wk[6]) + '/' + wk[6].slice(0, 4) : monLabel(mon)}
        </span>
        <IconButton icon="chevron-right" label="Next" appearance="secondary" size="sm" onClick={next} />
        <span className="spacer" />
        <div className="legend">
          <Legend items={[{ label: 'Actual ≤ plan', bg: 'var(--hl-ok)' }, { label: 'Actual > plan', bg: 'var(--hl-red)' }, { label: 'Day off', bg: 'var(--surface-hover)' }]} />
          <span>Hover a cell for worklogs · click to pin</span>
        </div>
      </div>
      <div style={{ overflowX: 'auto' }}>
        <div style={{ width: fit ? '100%' : `calc((100% - 260px) * ${(days.length / PER).toFixed(4)} + 260px)`, minWidth: 560, position: 'relative' }}>
          {ti >= 0 && <div className="today-frame" style={{ left: `calc(150px + (100% - 260px) * ${(ti / days.length).toFixed(5)})`, width: `calc((100% - 260px) / ${days.length})` }} />}
          <div style={{ display: 'flex', borderBottom: '1px solid var(--border-subtle)' }}>
            <div className="sticky-col" style={{ width: 150, padding: '8px 14px', fontSize: 11, color: 'var(--text-muted)' }}>Member</div>
            {days.map((s) => {
              const h = dayHead(s, today, extraDays);
              return (
                <div key={s} className="day-head" title={h.tip} style={{ flex: '1 1 0', minWidth: 0, padding: '4px 0', background: h.bg, color: h.fg }}>
                  <div>{WDN[dow(s)]}</div><div className="n" style={{ fontWeight: h.fw }}>{s.slice(8, 10)}</div>
                </div>
              );
            })}
            <div style={{ width: 110, flex: 'none', boxSizing: 'border-box', padding: '8px 12px', borderLeft: '1px solid var(--border-subtle)', fontSize: 11, color: 'var(--text-muted)', textAlign: 'right' }}>Logged</div>
          </div>
          {rows.map((r) => (
            <div key={r.id} style={{ display: 'flex', borderBottom: '1px solid var(--border-subtle)' }}>
              <div className="sticky-col" style={{ width: 150, padding: '6px 14px', display: 'flex', alignItems: 'center', gap: 8 }}>
                <Avatar a={{ ini: r.m.name[0], color: r.m.color }} size={22} />
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{r.m.name}</div>
                  <div style={{ fontSize: 10, color: r.miss ? 'var(--hl-red-fg)' : 'var(--text-muted)', whiteSpace: 'nowrap' }}>{r.m.role}{r.miss ? ` · ${r.miss} days over plan` : ''}</div>
                </div>
              </div>
              {r.cells.map((c) => (
                <button
                  key={c.s}
                  className="hv-dim2"
                  title={c.tip}
                  onClick={(ev) => { if (c.can) { setPin(pos(ev, r.id, c.s)); setHov(null); } }}
                  onMouseEnter={(ev) => { if (c.can && !pin) setHov(pos(ev, r.id, c.s)); }}
                  onMouseLeave={() => setHov(null)}
                  style={{
                    flex: '1 1 0', minWidth: 0, boxSizing: 'border-box', height: 46, padding: '0 2px', border: 0, borderLeft: '1px solid var(--border-subtle)',
                    background: c.bg, color: c.fg, font: '600 12px/1 var(--font-text)', cursor: c.can ? 'pointer' : 'default',
                    display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 4, overflow: 'hidden',
                  }}
                >
                  <span>{c.v}</span>
                  <span style={{ font: '500 10px/1 var(--font-text)', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>{c.pv}</span>
                </button>
              ))}
              <div style={{ width: 110, flex: 'none', boxSizing: 'border-box', padding: '6px 12px', borderLeft: '1px solid var(--border-subtle)', textAlign: 'right', display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
                <span style={{ fontWeight: 700, color: r.tot < r.exp ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{f1(r.tot)}h</span>
                <span style={{ fontSize: 10, color: 'var(--text-muted)' }}>of {f1(r.exp)}h</span>
              </div>
            </div>
          ))}
        </div>
      </div>

      {pc && pop && (
        <>
          {pin && <div onClick={() => setPin(null)} style={{ position: 'fixed', inset: 0, zIndex: 55 }} />}
          <div style={{
            position: 'fixed', top: pc.top, left: pc.left, zIndex: 56, pointerEvents: pin ? 'auto' : 'none', width: 360, maxHeight: 340, overflow: 'auto',
            borderRadius: 14, background: 'var(--surface-card)', border: '1px solid var(--border-subtle)', boxShadow: '0 12px 32px rgba(9,30,66,0.22)',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '12px 14px', borderBottom: '1px solid var(--border-subtle)' }}>
              <span className="title" style={{ flex: 1 }}>{pop.title}</span>
              <b className="heading">{pop.total}</b><span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{pop.exp}</span>
            </div>
            {pop.items.map((w) => (
              <div key={w.key} className="hv-bg" role="button" tabIndex={0}
                onClick={() => { if (w.key !== '—') { setPin(null); openTicket(w.key); } }}
                style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) auto', gap: '2px 10px', padding: '9px 14px', borderBottom: '1px solid var(--border-subtle)', cursor: 'pointer' }}>
                <span style={{ font: '600 11px/1.3 var(--font-mono)', color: 'var(--text-link)' }}>{w.key}</span>
                <b className="heading" style={{ textAlign: 'right' }}>{w.h}</b>
                <span className="ellipsis heading" style={{ fontSize: 12 }}>{w.title}</span>
                <span style={{ fontSize: 11, color: 'var(--text-muted)', textAlign: 'right', whiteSpace: 'nowrap' }}>{w.plan}</span>
                <span style={{ fontSize: 11, color: 'var(--text-muted)', gridColumn: '1 / -1' }}>{w.note}</span>
              </div>
            ))}
            {pop.empty && <div style={{ padding: '12px 14px', fontSize: 12, color: 'var(--hl-red-fg)' }}>No worklogs on this working day.</div>}
          </div>
        </>
      )}
    </section>
  );
}

function Bars({ list }: { list: [string, number, number][] }) {
  const mx = Math.max(1, ...list.flatMap((x) => [x[1], x[2]]));
  return (
    <>
      {list.map(([label, es, lg]) => {
        const r = es ? lg / es : 0;
        return (
          <div key={label} style={{ display: 'grid', gridTemplateColumns: '130px minmax(0,1fr) 120px', gap: 10, alignItems: 'center' }}>
            <span style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{label}</span>
            <div style={{ position: 'relative', height: 18 }}>
              <div style={{ position: 'absolute', left: 0, top: 0, height: 18, width: (es / mx) * 100 + '%', borderRadius: 5, background: 'var(--surface-tint)' }} />
              <div style={{ position: 'absolute', left: 0, top: 5, height: 8, width: (lg / mx) * 100 + '%', borderRadius: 4, background: lg > es ? 'var(--hl-red-fg)' : 'var(--surface-accent)' }} />
            </div>
            <span style={{ textAlign: 'right', fontSize: 12, color: 'var(--text-heading)' }}>
              {f1(lg)} / {f1(es)}h <b style={{ color: r > 1 ? 'var(--hl-red-fg)' : 'var(--text-muted)' }}>{Math.round(r * 100)}%</b>
            </span>
          </div>
        );
      })}
    </>
  );
}

const RW_GRID = 'minmax(150px,1.2fr) 80px 90px 110px minmax(180px,2fr) 90px 90px';

function Analysis() {
  const { snap } = useApp();
  const cells = allCells(snap);
  const ex = extras(snap);
  const started = (L: Task[]) => L.filter((c) => c.status !== 'N');
  const S = (L: Task[]): [number, number] => [L.reduce((a, c) => a + c.est, 0), L.reduce((a, c) => a + c.logged, 0)];
  const byPhase: [string, number, number][] = ([['Prepare', ex.filter((e) => e.phase === 'Prepare')], ['Implement_Coding', cells], ['Function Unit Test', ex.filter((e) => e.phase === 'Function Unit Test')]] as [string, Task[]][])
    .map(([l, L]) => [l, ...S(started(L))]);
  const byStep: [string, number, number][] = snap.steps.map((s, i) => [s.label, ...S(started(cells.filter((c) => c.si === i)))]);

  const bas = Object.keys(snap.members).filter((id) => snap.members[id].role === 'BA');
  const rw = bas.map((id) => {
    const L = cells.filter((c) => c.ba === id);
    const cr = L.filter((c) => c.sk === 'create').reduce((a, c) => a + c.logged, 0);
    const fx = L.filter((c) => c.isFix).reduce((a, c) => a + c.logged, 0);
    const fr = L.filter((c) => c.isFix && c.rounds > 0);
    return { id, cr, fx, rate: cr ? fx / cr : 0, avgR: fr.length ? fr.reduce((a, c) => a + c.rounds, 0) / fr.length : 0, mxR: Math.max(0, ...fr.map((c) => c.rounds)), n: new Set(L.map((c) => c.fid)).size };
  });
  const mxRate = Math.max(0.01, ...rw.map((r) => r.rate));
  const sc = started(cells);
  const [te, tl] = S(sc);
  const tc = rw.reduce((a, r) => a + r.cr, 0), tf = rw.reduce((a, r) => a + r.fx, 0);
  const fr = cells.filter((c) => c.isFix && c.rounds > 0);
  const anken = snap.anken.find((a) => a.matrix)?.name ?? '';
  const kpis = [
    ['Logged / estimate', te ? Math.round((tl / te) * 100) + '%' : '–', `${f1(tl)} / ${f1(te)}h on started sub-tasks`],
    ['Team rework rate', tc ? Math.round((tf / tc) * 100) + '%' : '–', 'Fix comment hours ÷ Create hours'],
    ['Avg review rounds', fr.length ? (fr.reduce((a, c) => a + c.rounds, 0) / fr.length).toFixed(1) : '–', fr.length + ' Fix comment tickets'],
    ['Over estimate', String(sc.filter((c) => c.over).length), 'sub-tasks'],
  ];

  return (
    <>
      <div className="card" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(150px,1fr))' }}>
        {kpis.map(([label, value, sub], i) => (
          <div key={label} style={{ padding: '14px 18px', borderLeft: i ? '1px solid var(--border-subtle)' : 0 }}>
            <div className="eyebrow">{label}</div>
            <div style={{ font: '700 24px/1.2 var(--font-display)', color: 'var(--text-heading)' }}>{value}</div>
            <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{sub}</div>
          </div>
        ))}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(min(100%,380px),1fr))', gap: 14 }}>
        <section className="card" style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div>
            <div className="title">Estimate vs logged · by phase</div>
            <div className="eyebrow">Started sub-tasks only · {anken}</div>
          </div>
          <Bars list={byPhase} />
          <div style={{ display: 'flex', gap: 14, fontSize: 11, color: 'var(--text-muted)', flexWrap: 'wrap' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}><span style={{ width: 16, height: 10, borderRadius: 3, background: 'var(--surface-tint)' }} />Estimate</span>
            <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}><span style={{ width: 16, height: 6, borderRadius: 3, background: 'var(--surface-accent)' }} />Logged</span>
            <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}><span style={{ width: 16, height: 6, borderRadius: 3, background: 'var(--hl-red-fg)' }} />Logged over estimate</span>
          </div>
        </section>
        <section className="card" style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div>
            <div className="title">Estimate vs logged · by step</div>
            <div className="eyebrow">Started sub-tasks only · Implement_Coding</div>
          </div>
          <Bars list={byStep} />
        </section>
      </div>
      <section className="card card-clip">
        <div style={{ padding: '14px 18px' }}>
          <div className="title">Rework by member</div>
          <div className="eyebrow">Rework rate = hours on Fix comment BAL/JM ÷ hours on Create</div>
        </div>
        <div style={{ overflowX: 'auto' }}>
          <div style={{ minWidth: 680 }}>
            <div style={{ display: 'grid', gridTemplateColumns: RW_GRID, gap: 12, padding: '8px 18px', background: 'var(--surface-hover)', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)' }}>
              <span>BA</span><span style={{ textAlign: 'right' }}>Functions</span><span style={{ textAlign: 'right' }}>Create</span><span style={{ textAlign: 'right' }}>Fix comment</span>
              <span>Rework rate</span><span style={{ textAlign: 'right' }}>Avg rounds</span><span style={{ textAlign: 'right' }}>Max rounds</span>
            </div>
            {rw.map((r) => {
              const a = av(snap, r.id);
              const hot = r.rate > 0.35;
              return (
                <div key={r.id} style={{ display: 'grid', gridTemplateColumns: RW_GRID, gap: 12, alignItems: 'center', padding: '10px 18px', borderTop: '1px solid var(--border-subtle)' }}>
                  <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}><Avatar a={a} size={22} /><b className="heading">{a.name}</b></span>
                  <span className="heading" style={{ textAlign: 'right' }}>{r.n}</span>
                  <span className="heading" style={{ textAlign: 'right' }}>{f1(r.cr)}h</span>
                  <span className="heading" style={{ textAlign: 'right' }}>{f1(r.fx)}h</span>
                  <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ flex: 1, height: 6, borderRadius: 99, background: 'var(--border-subtle)' }}>
                      <span style={{ display: 'block', height: 6, borderRadius: 99, width: (r.rate / mxRate) * 100 + '%', background: hot ? 'var(--hl-red-fg)' : 'var(--surface-accent)' }} />
                    </span>
                    <b style={{ width: 40, textAlign: 'right', color: hot ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{Math.round(r.rate * 100)}%</b>
                  </span>
                  <span style={{ textAlign: 'right', fontWeight: 600, color: 'var(--text-heading)' }}>{r.avgR ? r.avgR.toFixed(1) : '–'}</span>
                  <span style={{ textAlign: 'right', fontWeight: 600, color: r.mxR >= snap.config.rounds ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{r.mxR || '–'}</span>
                </div>
              );
            })}
          </div>
        </div>
      </section>
    </>
  );
}
