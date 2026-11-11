import type { CSSProperties } from 'react';
import type { Schemas } from '../../../shared/api/types';
import { Icon } from '../../../shared/ds';
import { dm, f2 } from '../../../shared/format/dates';
import { signedH } from '../../../shared/format/labels';
import { C, glow } from './metro';

type Group = Schemas['DailyGroup'];
type Burndown = Schemas['DailyBurndown'];

/**
 * The Process line: "All phases", then one stop per phase in the anken's order. Picking one sets the phase the header, the
 * burndown and the Function overview follow. Each stop: progress in the centre; its ring, the effort spent as a share of the
 * estimate, with a tick where the plan says the phase should be today (red gap: behind, green: ahead); its dates and hours.
 */
export function Process({ groups, burndowns, pct, phase, pick }: {
  groups: Group[]; burndowns: Burndown[]; pct: number; phase: string | undefined; pick: (phase: string | undefined) => void;
}) {
  const all = burndowns.find((b) => b.phase == null);
  const n = groups.length;
  return (
    <section className="card" aria-label="Process" style={{ padding: '16px 20px 8px', minWidth: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: 10 }}>
        <span style={{ font: '700 18px/1.2 var(--font-display)', color: 'var(--text-heading)' }}>Process</span>
        <button type="button" className="do-info" aria-label="About Process">
          <Icon name="info" size={17} />
          <span className="do-info-tip" role="tooltip">
            Phases in the order of <b>Setting phase</b>. Pick a phase to filter the header, the burndown and the functions below.
            <br /><br />
            <b>Centre</b>: progress of the phase. <b>Ring</b>: effort spent, as a share of the estimate. <b>Black tick</b>: where the plan says the
            phase should be today. The gap between the ring and the tick is <b style={{ color: C.late }}>red when behind</b>,{' '}
            <b style={{ color: C.done }}>green when ahead</b>. Hover a phase for its dates and hours.
          </span>
        </button>
        <span className="spacer" />
        <span style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap', fontSize: 12, color: 'var(--text-muted)' }}>
          <Swatch bg={C.wip} label="Effort spent" />
          <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}><span style={{ width: 3, height: 11, borderRadius: 1, background: 'var(--text-heading)' }} />Plan today</span>
          <Swatch bg={C.late} label="Behind" />
          <Swatch bg={C.done} label="Ahead" />
        </span>
      </div>
      <div style={{ display: 'flex', alignItems: 'stretch', gap: 14, overflowX: 'auto', overflowY: 'hidden', paddingBottom: 10 }}>
        <div style={{ flex: '1 1 0', minWidth: 150, display: 'flex' }}>
          <Hub name="All phases" sigma state="run" pct={pct} b={all} on={phase == null} pick={() => pick(undefined)} />
        </div>
        {n > 0 && <span aria-hidden="true" style={{ flex: 'none', width: 1, background: 'var(--border-subtle)', margin: '8px 0' }} />}
        {n > 0 && (
          <div style={{ flex: `${n} 1 0`, minWidth: n * 150, position: 'relative', display: 'grid', gridTemplateColumns: `repeat(${n},minmax(0,1fr))`, gap: 8 }}>
            {groups.slice(0, -1).map((g, i) => {
              const next = groups[i + 1];
              const bg = g.state === 'done' ? C.done : next.state !== 'not_started' ? C.wip : C.line;
              return <span key={g.name} aria-hidden="true" style={{
                position: 'absolute', top: 41.5, height: 3, borderRadius: 2, background: bg,
                left: `calc(${((i + 0.5) / n) * 100}% + 35px)`, width: `calc(${100 / n}% - 70px)`,
              }} />;
            })}
            {groups.map((g) => (
              <Hub key={g.name} name={g.name} state={g.state === 'done' ? 'done' : g.state === 'current' ? 'run' : 'nys'} pct={g.pct}
                b={burndowns.find((b) => b.phase === g.name)} on={phase === g.name} pick={() => pick(g.name)} />
            ))}
          </div>
        )}
      </div>
    </section>
  );
}

function Swatch({ bg, label }: { bg: string; label: string }) {
  return <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}><span style={{ width: 14, height: 6, borderRadius: 3, background: bg }} />{label}</span>;
}

/** One stop of the Process line. A phase that has not started is a plain grey circle with its name. */
function Hub({ name, sigma = false, state, pct, b, on, pick }: {
  name: string; sigma?: boolean; state: 'done' | 'run' | 'nys'; pct: number; b: Burndown | undefined; on: boolean; pick: () => void;
}) {
  const arrow = on && (
    <span aria-hidden="true" style={{
      position: 'absolute', bottom: -7, left: '50%', width: 12, height: 12, marginLeft: -6, transform: 'rotate(45deg)',
      borderRadius: '0 0 3px 0', background: 'var(--surface-card)', boxShadow: '1px 1px 0 0 var(--st-wip)',
    }} />
  );
  const p = b?.progress ?? null;
  if (state === 'nys' && !p) {
    return (
      <button type="button" className="do-hub" aria-pressed={on} onClick={pick} title={`${name} · not started`}>
        <span style={{ display: 'grid', placeItems: 'center', width: 66, height: 66 }}>
          <span style={{ width: 40, height: 40, borderRadius: '50%', background: 'var(--surface-card)', boxShadow: `inset 0 0 0 2px ${C.lineStrong}` }} />
        </span>
        <span style={{ font: '700 13px/1.2 var(--font-text)', color: 'var(--text-muted)' }}>{name}</span>
        <span style={{ font: '500 11.5px/1 var(--font-text)', color: 'var(--text-muted)' }}>Not started</span>
        {arrow}
      </button>
    );
  }
  const est = b?.chart.estimate_h ?? 0;
  let ring: string;
  let tick: number | null = null;
  if (state === 'done') ring = `conic-gradient(${C.done} 100%, var(--do-nys-bg) 0)`;
  else if (p && est > 0) {
    const a = Math.min(100, (p.actual_to_date_h / est) * 100);
    tick = Math.min(100, (p.plan_to_date_h / est) * 100);
    ring = a < tick
      ? `conic-gradient(${C.wip} 0 ${a}%, ${C.late} ${a}% ${tick}%, var(--do-nys-bg) ${tick}%)`
      : `conic-gradient(${C.wip} 0 ${tick}%, ${C.done} ${tick}% ${a}%, var(--do-nys-bg) ${a}%)`;
  } else ring = `conic-gradient(${C.wip} ${pct}%, var(--do-nys-bg) 0)`;
  const ahead = (p?.diff_h ?? 0) >= 0;
  const span = b ? `${dm(b.chart.start) || '?'} → ${dm(b.chart.end) || '?'}` : '';
  const tip = [name, span, p && est > 0 ? `effort ${f2(p.actual_to_date_h)}h of ${f2(est)}h estimated · plan by today ${f2(p.plan_to_date_h)}h` : '',
    p ? `${signedH(p.diff_h)} ${ahead ? 'ahead' : 'behind'}` : ''].filter(Boolean).join(' · ');
  const donut: CSSProperties = { position: 'relative', display: 'grid', placeItems: 'center', width: 66, height: 66, borderRadius: '50%', background: ring,
    ...(state === 'run' ? glow(C.wip) : {}) };
  return (
    <button type="button" className="do-hub" aria-pressed={on} onClick={pick} title={tip}>
      <span className={state === 'run' ? 'do-now' : undefined} style={donut}>
        <span style={{ display: 'grid', placeItems: 'center', width: 48, height: 48, borderRadius: '50%', background: 'var(--surface-card)', font: '800 15px/1 var(--font-text)', color: 'var(--text-heading)' }}>
          {pct}%
        </span>
        {tick != null && (
          <span aria-hidden="true" style={{ position: 'absolute', inset: 0, transform: `rotate(${tick * 3.6}deg)`, pointerEvents: 'none' }}>
            <span style={{ position: 'absolute', left: '50%', top: 0, width: 3, height: 9, marginLeft: -1.5, background: 'var(--text-heading)', boxShadow: '0 0 0 1px var(--surface-card)' }} />
          </span>
        )}
      </span>
      <span style={{ display: 'flex', alignItems: 'center', gap: 6, font: '800 14px/1.2 var(--font-text)', color: 'var(--text-heading)', textAlign: 'center' }}>
        {name}{sigma && <span className="do-tag">Σ</span>}
      </span>
      {p && (
        <span className="do-pill" style={{ height: 22, background: ahead ? 'var(--do-clo-bg)' : 'var(--do-red-bg)', color: ahead ? 'var(--do-clo)' : 'var(--do-red)', fontWeight: 800 }}>
          {ahead ? '▲' : '▼'} {signedH(p.diff_h)} {ahead ? 'ahead' : 'behind'}
        </span>
      )}
      <span style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2, font: '500 11px/1.25 var(--font-mono)', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
        {span && <span>{span}</span>}
        {p && est > 0 && <span>{f2(p.actual_to_date_h)}h / {f2(est)}h</span>}
      </span>
      {arrow}
    </button>
  );
}
