import { useState } from 'react';
import type { Schemas } from '../api/types';
import { D, dm, f1, WDN } from '../format/dates';

/**
 * Remaining effort per working day: the plan (dashed) and the actual remaining (solid), with a marker on `today`.
 * Point k is the end of day k, drawn at the centre of its column; day 0 is the baseline, the day before the start.
 */
export function BurndownChart({ b, today, todayLabel = 'Today', height = 320, empty }: {
  b: Schemas['BurndownChart']; today: string; todayLabel?: string; height?: number; empty: string;
}) {
  const [hov, setHov] = useState<number | null>(null);
  const days = b.days;
  const N = days.length;
  if (!N) return <div className="empty-row">{empty}</div>;
  const E = b.estimate_h;
  const plan = days.map((d) => d.plan_remaining_h);
  const act = days.flatMap((d) => (d.actual_remaining_h == null ? [] : [d.actual_remaining_h]));
  const ti = act.length - 1;
  const step = E > 400 ? 100 : E > 200 ? 50 : E > 80 ? 20 : 10;
  const yMax = Math.max(step, Math.ceil(E / step) * step);
  const X = (k: number) => (N ? ((k + 0.5) / N) * 1000 : 0).toFixed(1);
  const Y = (h: number) => (300 - (Math.max(0, h) / yMax) * 300).toFixed(1);
  const pts = (a: number[]) => a.map((h, k) => X(k) + ',' + Y(h)).join(' ');
  const every = Math.max(1, Math.ceil(N / 10));
  const showToday = ti >= 0 && !!b.end && today <= b.end;
  const hd = hov != null && hov < N ? days[hov] : null;

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '52px minmax(0,1fr)', gap: '0 8px' }}>
      <div style={{ position: 'relative', height }}>
        {[0, 1, 2, 3, 4].map((i) => (
          <span key={i} style={{ position: 'absolute', right: 0, top: i * 25 + '%', transform: 'translateY(-50%)', font: '500 11px/1 var(--font-mono)', color: 'var(--text-muted)' }}>
            {f1((yMax * (4 - i)) / 4)}h
          </span>
        ))}
      </div>
      <div
        role="img"
        aria-label={`Burndown: ${f1(b.plan_remaining_h)}h planned and ${f1(b.actual_remaining_h)}h actually remaining of ${f1(E)}h`}
        onMouseLeave={() => setHov(null)}
        style={{ position: 'relative', height, borderLeft: '1px solid var(--border-strong)', borderBottom: '1px solid var(--border-strong)' }}
      >
        {[0, 1, 2, 3].map((i) => <div key={i} style={{ position: 'absolute', left: 0, right: 0, top: i * 25 + '%', borderTop: '1px solid var(--border-subtle)' }} />)}
        <div style={{ position: 'absolute', inset: 0, display: 'flex' }}>
          {days.map((d, i) => (
            <div key={d.date} onMouseEnter={() => setHov(i)} style={{ flex: '1 1 0', minWidth: 0, background: hov === i ? 'color-mix(in oklch, var(--surface-tint) 45%, transparent)' : 'transparent' }} />
          ))}
        </div>
        {showToday && (
          <div style={{ position: 'absolute', top: 0, bottom: 0, left: ((ti + 0.5) / N) * 100 + '%', borderLeft: '1.5px solid var(--border-focus)', pointerEvents: 'none' }}>
            <span style={{ position: 'absolute', top: -2, left: 4, font: '600 10px/1 var(--font-text)', color: 'var(--text-link)', whiteSpace: 'nowrap' }}>{todayLabel}</span>
          </div>
        )}
        <svg viewBox="0 0 1000 300" preserveAspectRatio="none" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', overflow: 'visible', pointerEvents: 'none' }}>
          <polyline points={pts(plan)} fill="none" stroke="var(--text-muted)" strokeWidth={2} strokeDasharray="6 5" vectorEffect="non-scaling-stroke" />
          {ti >= 0 && <polyline points={pts(act)} fill="none" stroke="var(--st-wip)" strokeWidth={2.5} strokeLinejoin="round" vectorEffect="non-scaling-stroke" />}
        </svg>
        {hd && hov != null && <Tip d={hd} baseline={hov === 0} left={((hov + 0.5) / N) * 100} flip={hov > N * 0.6} />}
      </div>
      <div />
      <div style={{ position: 'relative', height: 22 }}>
        {days.map((d, i) => (i % every ? null : (
          <span key={d.date} style={{ position: 'absolute', top: 6, left: ((i + 0.5) / N) * 100 + '%', transform: 'translateX(-50%)', font: '500 11px/1 var(--font-mono)', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
            {dm(d.date)}
          </span>
        )))}
      </div>
    </div>
  );
}

function Tip({ d, baseline, left, flip }: { d: Schemas['BurndownDay']; baseline: boolean; left: number; flip: boolean }) {
  const a = d.actual_remaining_h;
  const row = { display: 'flex', justifyContent: 'space-between', gap: 12, color: 'var(--text-muted)' };
  return (
    <div style={{
      position: 'absolute', top: 12, left: left + '%', transform: flip ? 'translateX(calc(-100% - 12px))' : 'translateX(12px)', minWidth: 170,
      padding: '10px 12px', borderRadius: 8, background: 'var(--surface-card)', boxShadow: 'var(--shadow-overlay, 0 8px 24px rgba(9,30,66,.18))',
      border: '1px solid var(--border-subtle)', pointerEvents: 'none', display: 'flex', flexDirection: 'column', gap: 5, fontSize: 12, zIndex: 2,
    }}>
      <span style={{ fontWeight: 700, color: 'var(--text-heading)' }}>{WDN[D(d.date).getUTCDay()]} {dm(d.date)}{baseline ? ' · baseline' : ''}</span>
      <span style={row}>Plan<b style={{ color: 'var(--text-heading)' }}>{f1(d.plan_remaining_h)}h</b></span>
      <span style={row}>Actual<b style={{ color: 'var(--st-wip)' }}>{a == null ? '—' : f1(a) + 'h'}</b></span>
      <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{f1(d.planned_h)}h planned · {f1(d.logged_h)}h logged</span>
      {a != null && <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-heading)' }}>{(a - d.plan_remaining_h > 0 ? '+' : '') + f1(a - d.plan_remaining_h)}h vs plan</span>}
    </div>
  );
}
