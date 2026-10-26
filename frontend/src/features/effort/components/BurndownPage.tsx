import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import type { Schemas } from '../../../shared/api/types';
import { useMemberMap } from '../../../shared/api/queries';
import { Select } from '../../../shared/ds';
import { dm, f1 } from '../../../shared/format/dates';
import { useAnkenScope } from '../../../shared/state/anken';
import { Seg } from '../../../shared/ui/bits';
import { BurndownChart } from '../../../shared/ui/BurndownChart';
import { Loading, QueryError } from '../../../shared/ui/states';
import { burndownQuery } from '../api';
import { EffortHeader } from './EffortHeader';
import { PickAnken } from '../../../shared/ui/PickAnken';

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
  const b = v.burndown;
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
        <BurndownChart b={b} today={v.today}
          empty={`No sub-tasks with a Planned Start, a Planned End and an estimate${v.phase || v.task || v.member ? ' match these filters' : ' in this anken'}.`} />
      </section>
    </>
  );
}
