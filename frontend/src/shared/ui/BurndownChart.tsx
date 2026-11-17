import { useState } from 'react';
import type { Schemas } from '../api/types';
import { D, dm, f2, WDN } from '../format/dates';

/** Width of an off day's column against a working day's. */
const OFF_W = 0.4;

/** Which lines a burndown draws. */
export interface BurndownLines { baseline: boolean; plan: boolean; actual: boolean }
const ALL_LINES: BurndownLines = { baseline: true, plan: true, actual: true };

/**
 * Remaining effort per day: the plan (dashed), the actual remaining (solid; a dot when it has a single day) and the Baseline plan
 * when the anken has one (dotted), with a marker on `today`. Point k is the end of day k, drawn at the centre of its column; day 0
 * is the baseline, the day before the start. Off days have a narrower, grey column. `show` hides lines. With `onPick`, a day
 * is picked by a click and `picked` is marked.
 */
export function BurndownChart({ b, today, todayLabel = 'Today', height = 320, empty, show = ALL_LINES, picked, onPick }: {
  b: Schemas['BurndownChart']; today: string; todayLabel?: string; height?: number; empty: string; show?: BurndownLines;
  picked?: string; onPick?: (date: string) => void;
}) {
  const [hov, setHov] = useState<number | null>(null);
  const days = b.days;
  const N = days.length;
  if (!N) return <div className="empty-row">{empty}</div>;
  const E = b.estimate_h;
  const plan = days.map((d) => d.plan_remaining_h);
  const act = days.flatMap((d, k) => (d.actual_remaining_h == null ? [] : [[k, d.actual_remaining_h] as const]));
  const base = days.flatMap((d, k) => (d.baseline_remaining_h == null ? [] : [[k, d.baseline_remaining_h] as const]));
  const top = Math.max(E, b.baseline_h ?? 0, ...plan, ...act.map(([, h]) => h), ...base.map(([, h]) => h));
  const ti = days.findLastIndex((d) => d.date <= today);
  const step = top > 400 ? 100 : top > 200 ? 50 : top > 80 ? 20 : 10;
  const yMax = Math.max(step, Math.ceil(top / step) * step);
  const w = days.map((d) => (d.off ? OFF_W : 1));
  const total = w.reduce((a, x) => a + x, 0);
  const mid: number[] = [];
  w.reduce((left, x) => (mid.push((left + x / 2) / total), left + x), 0);
  const X = (k: number) => (mid[k] * 1000).toFixed(1);
  const Y = (h: number) => (300 - (Math.max(0, h) / yMax) * 300).toFixed(1);
  const pts = (a: (readonly [number, number])[]) => a.map(([k, h]) => X(k) + ',' + Y(h)).join(' ');
  const labelled = days.flatMap((d, i) => (d.off ? [] : [i]));
  const every = Math.max(1, Math.ceil(labelled.length / 10));
  const showToday = ti >= 0 && !!b.end && today <= b.end;
  const hd = hov != null && hov < N ? days[hov] : null;

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '52px minmax(0,1fr)', gap: '0 8px' }}>
      <div style={{ position: 'relative', height }}>
        {[0, 1, 2, 3, 4].map((i) => (
          <span key={i} style={{ position: 'absolute', right: 0, top: i * 25 + '%', transform: 'translateY(-50%)', font: '500 11px/1 var(--font-mono)', color: 'var(--text-muted)' }}>
            {f2((yMax * (4 - i)) / 4)}h
          </span>
        ))}
      </div>
      <div
        role="img"
        aria-label={`Burndown: ${f2(b.plan_remaining_h)}h planned and ${f2(b.actual_remaining_h)}h actually remaining of ${f2(E)}h`}
        onMouseLeave={() => setHov(null)}
        style={{ position: 'relative', height, borderLeft: '1px solid var(--border-strong)', borderBottom: '1px solid var(--border-strong)' }}
      >
        <div style={{ position: 'absolute', inset: 0, display: 'flex' }}>
          {days.map((d, i) => (
            <div key={d.date} data-day={d.date} data-off={d.off || undefined} data-picked={(onPick && d.date === picked) || undefined}
              onMouseEnter={() => setHov(i)} onClick={onPick && (() => onPick(d.date))} style={{
                flexGrow: w[i], flexBasis: 0, minWidth: 0, cursor: onPick ? 'pointer' : undefined,
                background: onPick && d.date === picked ? 'color-mix(in oklch, var(--surface-tint) 80%, transparent)'
                  : hov === i ? 'color-mix(in oklch, var(--surface-tint) 45%, transparent)' : d.off ? 'var(--surface-hover)' : 'transparent',
                boxShadow: onPick && d.date === picked ? 'inset 0 0 0 1px var(--border-focus)' : undefined,
              }} />
          ))}
        </div>
        {[0, 1, 2, 3].map((i) => <div key={i} style={{ position: 'absolute', left: 0, right: 0, top: i * 25 + '%', borderTop: '1px solid var(--border-subtle)', pointerEvents: 'none' }} />)}
        {showToday && (
          <div style={{ position: 'absolute', top: 0, bottom: 0, left: mid[ti] * 100 + '%', borderLeft: '1.5px solid var(--border-focus)', pointerEvents: 'none' }}>
            <span style={{ position: 'absolute', top: -2, left: 4, font: '600 10px/1 var(--font-text)', color: 'var(--text-link)', whiteSpace: 'nowrap' }}>{todayLabel}</span>
          </div>
        )}
        <svg viewBox="0 0 1000 300" preserveAspectRatio="none" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', overflow: 'visible', pointerEvents: 'none' }}>
          {show.baseline && base.length > 0 && <polyline data-line="baseline" points={pts(base)} fill="none" stroke="var(--text-link)" strokeWidth={2} strokeDasharray="1 4" strokeLinecap="round" vectorEffect="non-scaling-stroke" />}
          {show.plan && <polyline data-line="plan" points={pts(plan.map((h, k) => [k, h] as const))} fill="none" stroke="var(--text-muted)" strokeWidth={2} strokeDasharray="6 5" vectorEffect="non-scaling-stroke" />}
          {show.actual && act.length > 1 && <polyline data-line="actual" points={pts(act)} fill="none" stroke="var(--st-wip)" strokeWidth={2.5} strokeLinejoin="round" vectorEffect="non-scaling-stroke" />}
        </svg>
        {show.actual && act.length === 1 && act.map(([k, h]) => (
          <span key={k} data-actual={days[k].date} style={{ position: 'absolute', left: mid[k] * 100 + '%', top: (1 - Math.max(0, h) / yMax) * 100 + '%', width: 7, height: 7,
            marginLeft: -3.5, marginTop: -3.5, borderRadius: '50%', background: 'var(--st-wip)', pointerEvents: 'none' }} />
        ))}
        {hd && hov != null && <Tip d={hd} baseline={hov === 0} left={mid[hov] * 100} flip={mid[hov] > 0.6} />}
      </div>
      <div />
      <div style={{ position: 'relative', height: 22 }}>
        {labelled.map((i, n) => (n % every ? null : (
          <span key={days[i].date} style={{ position: 'absolute', top: 6, left: mid[i] * 100 + '%', transform: 'translateX(-50%)', font: '500 11px/1 var(--font-mono)', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
            {dm(days[i].date)}
          </span>
        )))}
      </div>
    </div>
  );
}

function Tip({ d, baseline, left, flip }: { d: Schemas['BurndownDay']; baseline: boolean; left: number; flip: boolean }) {
  const a = d.actual_remaining_h;
  const gap = a == null ? 0 : a - d.plan_remaining_h;
  const row = { display: 'flex', justifyContent: 'space-between', gap: 12, color: 'var(--text-muted)' };
  return (
    <div style={{
      position: 'absolute', top: 12, left: left + '%', transform: flip ? 'translateX(calc(-100% - 12px))' : 'translateX(12px)', minWidth: 170,
      padding: '10px 12px', borderRadius: 8, background: 'var(--surface-card)', boxShadow: 'var(--shadow-overlay, 0 8px 24px rgba(9,30,66,.18))',
      border: '1px solid var(--border-subtle)', pointerEvents: 'none', display: 'flex', flexDirection: 'column', gap: 5, fontSize: 12, zIndex: 2,
    }}>
      <span style={{ fontWeight: 700, color: 'var(--text-heading)' }}>{WDN[D(d.date).getUTCDay()]} {dm(d.date)}{baseline ? ' · baseline' : ''}</span>
      {d.baseline_remaining_h != null && <span style={row}>Baseline plan<b style={{ color: 'var(--text-link)' }}>{f2(d.baseline_remaining_h)}h</b></span>}
      <span style={row}>Plan<b style={{ color: 'var(--text-heading)' }}>{f2(d.plan_remaining_h)}h</b></span>
      <span style={row}>Actual<b style={{ color: 'var(--st-wip)' }}>{a == null ? '—' : f2(a) + 'h'}</b></span>
      {a != null && (
        <span style={{ fontSize: 11, fontWeight: 600, color: gap > 0.005 ? 'var(--hl-red-fg)' : gap < -0.005 ? 'var(--tn-res)' : 'var(--text-heading)' }}>
          {(gap > 0 ? '+' : '') + f2(gap)}h vs plan
        </span>
      )}
    </div>
  );
}
