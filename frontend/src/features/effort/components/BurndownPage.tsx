import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { useState } from 'react';
import type { Schemas } from '../../../shared/api/types';
import { useMemberMap } from '../../../shared/api/queries';
import { Select } from '../../../shared/ds';
import { D, dm, f1, WDN } from '../../../shared/format/dates';
import { useAnkenScope } from '../../../shared/state/anken';
import { Seg } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { burndownQuery } from '../api';
import { EffortHeader } from './EffortHeader';
import { PickAnken } from './PickAnken';

const route = getRouteApi('/app/effort/burndown');
const ALL = '';
const signed = (n: number) => (n > 0 ? '+' : n < 0 ? '−' : '') + f1(Math.abs(n)) + 'h';

export function BurndownPage() {
  const { anken } = useAnkenScope();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, width: '100%' }}>
      <EffortHeader tab="burndown" />
      {anken ? <BurndownOf anken={anken} /> : <PickAnken />}
    </div>
  );
}

/** The anken is picked in the global switcher; changing it drops the phase, sub-phase and member (see the route). */
function BurndownOf({ anken }: { anken: string }) {
  const { phase, task, member } = route.useSearch();
  const navigate = route.useNavigate();
  const members = useMemberMap();
  const q = useQuery(burndownQuery({ phase, task, member }, anken));
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  const v = q.data;
  return (
    <>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <Seg<string>
          items={[[ALL, 'All phases'], ...v.phases.map((p): [string, string] => [p, p])]}
          value={v.phase ?? ALL}
          onPick={(p) => navigate({ search: (s) => ({ ...s, phase: p || undefined, task: undefined }) })}
        />
        {v.phase && v.tasks.length > 0 && (
          <Select
            aria-label="Sub-phase"
            style={{ width: 220 }}
            value={v.task ?? ALL}
            options={[{ value: ALL, label: 'All sub-phases' }, ...v.tasks.map((t) => ({ value: t.key, label: t.label }))]}
            onChange={(t) => navigate({ search: (s) => ({ ...s, task: t || undefined }) })}
          />
        )}
        <Select
          aria-label="Member"
          style={{ width: 200 }}
          value={v.member ?? ALL}
          options={[{ value: ALL, label: 'All members' }, ...v.members.map((id) => ({ value: id, label: members[id]?.name ?? id }))]}
          onChange={(m) => navigate({ search: (s) => ({ ...s, member: m || undefined }) })}
        />
        <span className="spacer" />
        <span className="eyebrow">{v.title} · working days only</span>
      </div>
      <Burndown v={v} />
    </>
  );
}

function Burndown({ v }: { v: Schemas['BurndownView'] }) {
  const [hov, setHov] = useState<number | null>(null);
  const b = v.burndown;
  const days = b.days;
  const N = days.length;
  const E = b.estimate_h;
  const gap = b.actual_remaining_h - b.plan_remaining_h;
  const kpis = [
    { label: 'Total estimate', value: f1(E) + 'h', sub: `${v.subtasks} sub-tasks · ${dm(b.start)} – ${dm(b.end)}`, fg: 'var(--text-heading)' },
    { label: 'Planned remaining', value: f1(b.plan_remaining_h) + 'h', sub: 'by plan, end of ' + dm(v.today), fg: 'var(--text-heading)' },
    { label: 'Actual remaining', value: f1(b.actual_remaining_h) + 'h', sub: 'estimate − logged', fg: 'var(--st-wip)' },
    gap > 0.05 ? { label: 'Behind plan', value: signed(gap), sub: 'actual vs planned remaining', fg: 'var(--hl-red-fg)' }
      : gap < -0.05 ? { label: 'Ahead of plan', value: signed(gap), sub: 'actual vs planned remaining', fg: 'var(--tn-res)' }
        : { label: 'On plan', value: signed(0), sub: 'actual vs planned remaining', fg: 'var(--text-heading)' },
  ];

  // Point k is the end of day k, drawn at the centre of its column; day 0 is the baseline, the day before the start.
  const plan = days.map((d) => d.plan_remaining_h);
  const act = days.flatMap((d) => (d.actual_remaining_h == null ? [] : [d.actual_remaining_h]));
  const ti = act.length - 1;
  const step = E > 400 ? 100 : E > 200 ? 50 : E > 80 ? 20 : 10;
  const yMax = Math.max(step, Math.ceil(E / step) * step);
  const X = (k: number) => (N ? ((k + 0.5) / N) * 1000 : 0).toFixed(1);
  const Y = (h: number) => (300 - (Math.max(0, h) / yMax) * 300).toFixed(1);
  const pts = (a: number[]) => a.map((h, k) => X(k) + ',' + Y(h)).join(' ');
  const every = Math.max(1, Math.ceil(N / 10));
  const showToday = ti >= 0 && !!b.end && v.today <= b.end;
  const hd = hov != null && hov < N ? days[hov] : null;

  return (
    <>
      <div className="card" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(150px,1fr))' }}>
        {kpis.map((k, i) => (
          <div key={k.label} style={{ padding: '14px 18px', borderLeft: i ? '1px solid var(--border-subtle)' : 0 }}>
            <div className="eyebrow">{k.label}</div>
            <div style={{ font: '700 24px/1.2 var(--font-display)', color: k.fg }}>{k.value}</div>
            <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{k.sub}</div>
          </div>
        ))}
      </div>
      <section className="card" style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, flexWrap: 'wrap' }}>
          <div style={{ flex: 1, minWidth: 220 }}>
            <div className="title">Effort burndown</div>
            <div className="eyebrow">Remaining hours from the day before the start. Plan spreads each estimate evenly over its Planned Start–End; actual subtracts logged hours.</div>
          </div>
          <div style={{ display: 'flex', gap: 14, fontSize: 12, color: 'var(--text-muted)' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}><span style={{ width: 20, height: 0, borderTop: '2px dashed var(--text-muted)' }} />Plan</span>
            <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}><span style={{ width: 20, height: 3, borderRadius: 2, background: 'var(--st-wip)' }} />Actual</span>
            <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}><span style={{ width: 0, height: 12, borderLeft: '1.5px solid var(--border-focus)' }} />Today</span>
          </div>
        </div>
        {!N ? (
          <div className="empty-row">No sub-tasks with a Planned Start, a Planned End and an estimate{v.phase || v.task || v.member ? ' match these filters' : ' in this anken'}.</div>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: '52px minmax(0,1fr)', gap: '0 8px' }}>
            <div style={{ position: 'relative', height: 320 }}>
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
              style={{ position: 'relative', height: 320, borderLeft: '1px solid var(--border-strong)', borderBottom: '1px solid var(--border-strong)' }}
            >
              {[0, 1, 2, 3].map((i) => <div key={i} style={{ position: 'absolute', left: 0, right: 0, top: i * 25 + '%', borderTop: '1px solid var(--border-subtle)' }} />)}
              <div style={{ position: 'absolute', inset: 0, display: 'flex' }}>
                {days.map((d, i) => (
                  <div key={d.date} onMouseEnter={() => setHov(i)} style={{ flex: '1 1 0', minWidth: 0, background: hov === i ? 'color-mix(in oklch, var(--surface-tint) 45%, transparent)' : 'transparent' }} />
                ))}
              </div>
              {showToday && (
                <div style={{ position: 'absolute', top: 0, bottom: 0, left: ((ti + 0.5) / N) * 100 + '%', borderLeft: '1.5px solid var(--border-focus)', pointerEvents: 'none' }}>
                  <span style={{ position: 'absolute', top: -2, left: 4, font: '600 10px/1 var(--font-text)', color: 'var(--text-link)' }}>Today</span>
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
        )}
      </section>
    </>
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
