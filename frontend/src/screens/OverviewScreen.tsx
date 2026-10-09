import { useState } from 'react';
import type { Anken, Task } from '../api/types';
import { Avatar } from '../components/bits';
import { Button, Icon, type IconName } from '../ds';
import { dm, wd } from '../lib/dates';
import { allCells, av, extras, pct, short, STAT, statics } from '../lib/domain';
import { useApp } from '../state/AppContext';

const PH_W = [0.15, 0.6, 0.25];
const PH_N = ['Prepare', 'Implement_Coding', 'Function Unit Test'];
const PH_S = ['Prepare', 'Implement', 'UT'];

type Full = Required<Omit<Anken, 'matrix'>> & { matrix?: boolean };

export function OverviewScreen() {
  const { snap, go, openTicket } = useApp();
  const [sel, setSel] = useState(snap.anken[0]?.key);
  const cfg = snap.config;
  const cells = allCells(snap);
  const pay = [...cells, ...extras(snap)];

  // The tracked anken: rollup from the epic, with Implement progress, late functions and effort computed from the matrix.
  const live = (a: Anken): Full => {
    const fns = snap.functions;
    const impl = fns.length ? Math.round(fns.reduce((s, f) => s + pct(f), 0) / fns.length) : 0;
    const ut = extras(snap).filter((c) => c.phase === 'Function Unit Test');
    const phases = [...(a.phases ?? [0, 0, 0])];
    phases[1] = impl;
    const counts = [...(a.counts ?? ['', '', ''])];
    counts[1] = `${cells.filter((c) => c.status === 'C').length}/${cells.length}`;
    return {
      ...(a as Full), phases, counts,
      late: fns.filter((f) => f.cells.some((c) => c.late)).length,
      used: Math.round((a.baseUsed ?? 0) + [...cells, ...ut].reduce((s, c) => s + c.logged, 0)),
      est: Math.round((a.baseEst ?? 0) + cells.reduce((s, c) => s + c.est, 0)),
    };
  };
  const ank = snap.anken.map((a) => (a.matrix ? live(a) : (a as Full)));
  const A = ank.find((a) => a.key === sel) ?? ank[0];
  const pool = A.matrix ? pay : statics(snap).filter((s) => s.anken === A.key);
  const isJM = (id: string) => snap.members[id]?.role === 'JM';
  const ATT: { label: string; icon: IconName; list: Task[]; meta: (c: Task) => string }[] = [
    { label: 'Overdue', icon: 'alarm-clock', list: pool.filter((c) => c.late).sort((a, b) => b.lateDays - a.lateDays), meta: (c) => `late ${c.lateDays}d` },
    { label: 'Stuck in Resolved', icon: 'pause-circle', list: pool.filter((c) => c.status === 'R' && c.held >= cfg.held), meta: (c) => `${c.held} days` },
    { label: 'Many review rounds', icon: 'refresh-cw', list: pool.filter((c) => c.rounds >= cfg.rounds && c.status !== 'C'), meta: (c) => `round ${c.rounds}` },
    { label: 'Waiting long on client (JM)', icon: 'hourglass', list: pool.filter((c) => isJM(c.assignee) && c.status !== 'C' && c.held >= cfg.held), meta: (c) => `${c.held} days` },
  ];
  const curPhase = A.phases.findIndex((x) => x < 100);
  const prj = snap.anken[0]?.key.split('-')[0] ?? 'PAY';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18, width: '100%' }}>
      <div>
        <div className="eyebrow">Project {prj} · {snap.anken.length} active anken</div>
        <h1 className="h1" style={{ marginTop: 2 }}>Anken Overview</h1>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(290px,1fr))', gap: 14 }}>
        {ank.map((a) => {
          const on = a.key === A.key;
          const r = a.used / a.est;
          const fc = wd(a.plan, a.forecast);
          const lateFc = a.forecast > a.plan;
          return (
            <div key={a.key} className="hv-bd" role="button" tabIndex={0} onClick={() => setSel(a.key)} onKeyDown={(e) => e.key === 'Enter' && setSel(a.key)} style={{
              display: 'flex', flexDirection: 'column', gap: 12, padding: '14px 16px', borderRadius: 14, background: 'var(--surface-card)', cursor: 'pointer',
              border: `1px solid ${on ? 'var(--border-focus)' : 'var(--border-subtle)'}`, boxShadow: on ? '0 0 0 1px var(--border-focus)' : 'none',
            }}>
              <div>
                <div style={{ font: '600 11px/1 var(--font-mono)', color: 'var(--text-muted)' }}>{a.key}</div>
                <div style={{ marginTop: 4, font: '700 15px/1.3 var(--font-display)', color: 'var(--text-heading)' }}>{a.name}</div>
              </div>
              <div>
                <div style={{ display: 'flex', gap: 3, height: 8 }}>
                  {a.phases.map((p, i) => (
                    <div key={i} title={`${PH_N[i]} ${p}%`} style={{ flex: PH_W[i], borderRadius: 99, background: 'var(--surface-tint)', overflow: 'hidden' }}>
                      <div style={{ height: '100%', width: p + '%', background: 'var(--surface-accent)' }} />
                    </div>
                  ))}
                </div>
                <div style={{ display: 'flex', gap: 10, marginTop: 6, fontSize: 11, color: 'var(--text-muted)' }}>
                  {a.phases.map((p, i) => <span key={i} style={{ whiteSpace: 'nowrap' }}>{PH_S[i]} <b className="heading">{p}%</b></span>)}
                </div>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px 12px', fontSize: 12 }}>
                <div>
                  <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>Plan → Forecast</div>
                  <div style={{ fontWeight: 600, color: 'var(--text-heading)' }}>
                    {dm(a.plan)} → <span style={{ color: lateFc ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{dm(a.forecast)}{lateFc ? ` (+${fc}d)` : ''}</span>
                  </div>
                </div>
                <div>
                  <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>Late functions</div>
                  <div style={{ fontWeight: 600, color: a.late ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{a.late ? a.late + ' function' : 'None'}</div>
                </div>
                <div style={{ gridColumn: '1 / -1' }}>
                  <div style={{ display: 'flex', fontSize: 11, color: 'var(--text-muted)' }}>
                    <span style={{ flex: 1 }}>Effort used / estimate</span>
                    <span style={{ fontWeight: 600, color: r > 1 ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{a.used} / {a.est}h</span>
                  </div>
                  <div style={{ height: 4, borderRadius: 99, background: 'var(--border-subtle)', marginTop: 4 }}>
                    <div style={{ height: 4, borderRadius: 99, width: Math.min(100, r * 100) + '%', background: r > 1 ? 'var(--hl-red-fg)' : 'var(--surface-accent)' }} />
                  </div>
                </div>
              </div>
              <div style={{ display: 'flex', alignItems: 'center' }}>
                {a.members.map((id) => {
                  const m = av(snap, id);
                  return <Avatar key={id} a={m} size={22} title={m.name} style={{ marginRight: -5, border: '2px solid var(--surface-card)', lineHeight: '18px' }} />;
                })}
              </div>
            </div>
          );
        })}
      </div>

      <section className="card" style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <div style={{ flex: 1 }}>
            <div style={{ font: '600 11px/1 var(--font-mono)', color: 'var(--text-muted)' }}>{A.key}</div>
            <div style={{ marginTop: 4, font: '700 18px/1.2 var(--font-display)', color: 'var(--text-heading)' }}>{A.name}</div>
          </div>
          {A.matrix && <Button appearance="secondary" size="sm" iconAfter="arrow-right" onClick={() => go('matrix')}>Open Function Matrix</Button>}
        </div>
        <div style={{ display: 'flex', alignItems: 'stretch', gap: 6, flexWrap: 'wrap' }}>
          {A.phases.map((p, i) => (
            <div key={i} style={{ flex: '1 1 200px', display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
              <div style={{ flex: 1, padding: '12px 14px', borderRadius: 14, minWidth: 0, background: p !== 100 && i === curPhase ? 'var(--surface-tint)' : 'var(--surface-hover)' }}>
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
                  <span className="title">{PH_N[i]}</span>
                  <span className="spacer" />
                  <span style={{ font: '700 22px/1 var(--font-display)', color: 'var(--text-heading)' }}>{p}%</span>
                </div>
                <div style={{ height: 5, borderRadius: 6, background: 'var(--border-subtle)', margin: '10px 0 8px' }}>
                  <div style={{ height: 5, borderRadius: 6, width: p + '%', background: 'var(--surface-accent)' }} />
                </div>
                <div style={{ display: 'flex', fontSize: 11, color: 'var(--text-muted)' }}>
                  <span style={{ flex: 1 }}>{A.dates[i]}</span><span>{A.counts[i]} sub-task closed</span>
                </div>
              </div>
              {i < 2 && <span style={{ color: 'var(--text-muted)', display: 'flex' }}><Icon name="chevron-right" size={16} /></span>}
            </div>
          ))}
        </div>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
            <Icon name="triangle-alert" size={16} />
            <span className="title">Needs attention</span>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(250px,1fr))', gap: 12 }}>
            {ATT.map((g) => (
              <div key={g.label} style={{ border: '1px solid var(--border-subtle)', borderRadius: 14, overflow: 'hidden' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 12px', background: 'var(--surface-hover)' }}>
                  <Icon name={g.icon} size={14} />
                  <span style={{ fontWeight: 600, color: 'var(--text-heading)', flex: 1 }}>{g.label}</span>
                  <span className="count" style={{ background: g.list.length ? 'var(--hl-red)' : 'var(--surface-hover)', color: g.list.length ? 'var(--hl-red-fg)' : 'var(--text-muted)' }}>{g.list.length}</span>
                </div>
                {g.list.slice(0, 6).map((c) => {
                  const a = av(snap, c.assignee);
                  return (
                    <div key={c.key} className="hv-bg" role="button" tabIndex={0} onClick={() => openTicket(c.key)} onKeyDown={(e) => e.key === 'Enter' && openTicket(c.key)}
                      style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '7px 12px', borderTop: '1px solid var(--border-subtle)', cursor: 'pointer' }}>
                      <span className="dot" style={{ width: 7, height: 7, background: `var(--st-${STAT[c.status].v})` }} />
                      <span style={{ font: '600 11px/1 var(--font-mono)', color: 'var(--text-link)', flex: 'none' }}>{c.key}</span>
                      <span className="ellipsis heading" style={{ flex: 1, minWidth: 0 }}>{short(c)}</span>
                      <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>{g.meta(c)}</span>
                      <Avatar a={a} size={18} />
                    </div>
                  );
                })}
                {!g.list.length && <div style={{ padding: '8px 12px', borderTop: '1px solid var(--border-subtle)', fontSize: 12, color: 'var(--text-muted)' }}>None.</div>}
              </div>
            ))}
          </div>
        </div>
      </section>
    </div>
  );
}
