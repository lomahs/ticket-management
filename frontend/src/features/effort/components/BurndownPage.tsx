import { useQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { getRouteApi } from '@tanstack/react-router';
import type { Schemas } from '../../../shared/api/types';
import { useMemberMap } from '../../../shared/api/queries';
import { Select } from '../../../shared/ds';
import { dm, f2 } from '../../../shared/format/dates';
import { SCHEDULE_LABEL } from '../../../shared/format/labels';
import { useAnkenScope } from '../../../shared/state/anken';
import { Seg } from '../../../shared/ui/bits';
import { BurndownChart, type BurndownLines } from '../../../shared/ui/BurndownChart';
import { Loading, QueryError } from '../../../shared/ui/states';
import { burndownQuery } from '../api';
import { EffortHeader } from './EffortHeader';
import { PickAnken } from '../../../shared/ui/PickAnken';
import { PlanTable } from './PlanTable';

const route = getRouteApi('/app/effort/burndown');
const ALL = '';
const signed = (n: number) => (n > 0 ? '+' : n < 0 ? '−' : '') + f2(Math.abs(n)) + 'h';

/** A legend item that shows or hides its line. */
function LineToggle({ on, flip, mark, children }: { on: boolean; flip: () => void; mark: ReactNode; children: string }) {
  return (
    <button type="button" aria-pressed={on} onClick={flip} title={on ? `Hide ${children}` : `Show ${children}`} style={{
      display: 'flex', alignItems: 'center', gap: 6, padding: '2px 6px', border: 0, borderRadius: 6, background: 'transparent', cursor: 'pointer',
      font: 'inherit', color: 'var(--text-muted)', opacity: on ? 1 : 0.45, textDecoration: on ? undefined : 'line-through',
    }}>{mark}{children}</button>
  );
}

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
        <span className="eyebrow">{v.title} · off days in grey</span>
      </div>
      <Burndown v={v} />
    </>
  );
}

function Burndown({ v }: { v: Schemas['BurndownView'] }) {
  const b = v.burndown;
  // which lines show: not remembered, every line again on the next visit
  const [show, setShow] = useState<BurndownLines>({ baseline: true, plan: true, actual: true });
  const flip = (k: keyof BurndownLines) => () => setShow((s) => ({ ...s, [k]: !s[k] }));
  const E = b.estimate_h;
  const gap = b.actual_remaining_h - b.plan_remaining_h;
  const kpis = [
    { label: 'Total estimate', value: f2(E) + 'h', sub: `${v.subtasks} sub-tasks · ${dm(b.start)} – ${dm(b.end)}`, fg: 'var(--text-heading)' },
    { label: 'Planned remaining', value: f2(b.plan_remaining_h) + 'h', sub: 'by plan, end of ' + dm(v.today), fg: 'var(--text-heading)' },
    { label: 'Actual remaining', value: f2(b.actual_remaining_h) + 'h', sub: 'Jira remaining estimate', fg: 'var(--st-wip)' },
    gap > 0.005 ? { label: SCHEDULE_LABEL.under_burn, value: signed(gap), sub: 'actual vs planned remaining', fg: 'var(--hl-red-fg)' }
      : gap < -0.005 ? { label: SCHEDULE_LABEL.over_burn, value: signed(gap), sub: 'actual vs planned remaining', fg: 'var(--tn-res)' }
        : { label: SCHEDULE_LABEL.on_plan, value: signed(0), sub: 'actual vs planned remaining', fg: 'var(--text-heading)' },
  ];

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
            <div className="eyebrow">Remaining hours from the day before the start. Plan counts down by the hours planned per day (Detail Planning, else the estimate spread evenly); actual is Jira's remaining estimate: today live, an earlier day as its Daily Overview capture saw it, else what remains now plus the hours logged since. An Original Estimate change counts from the first day. Baseline plan: the first version saved on Planning › Detail plan.</div>
          </div>
          <div style={{ display: 'flex', gap: 8, fontSize: 12, color: 'var(--text-muted)', alignItems: 'center' }}>
            {b.baseline_h != null && (
              <LineToggle on={show.baseline} flip={flip('baseline')} mark={<span style={{ width: 20, height: 0, borderTop: '2px dotted var(--text-link)' }} />}>Baseline plan</LineToggle>
            )}
            <LineToggle on={show.plan} flip={flip('plan')} mark={<span style={{ width: 20, height: 0, borderTop: '2px dashed var(--text-muted)' }} />}>Plan</LineToggle>
            <LineToggle on={show.actual} flip={flip('actual')} mark={<span style={{ width: 20, height: 3, borderRadius: 2, background: 'var(--st-wip)' }} />}>Actual</LineToggle>
            <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}><span style={{ width: 0, height: 12, borderLeft: '1.5px solid var(--border-focus)' }} />Today</span>
          </div>
        </div>
        <BurndownChart b={b} today={v.today} show={show}
          empty={`No sub-tasks with a Planned Start, a Planned End and an estimate${v.phase || v.task || v.member ? ' match these filters' : ' in this anken'}.`} />
      </section>
      <PlanTable m={v.matrix} today={v.today} />
    </>
  );
}
