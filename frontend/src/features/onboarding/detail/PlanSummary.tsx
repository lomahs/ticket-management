import { useIsMutating } from '@tanstack/react-query';
import { useState, type CSSProperties } from 'react';
import { Icon } from '../../../shared/ds';
import { dm, hrs } from '../../../shared/format/dates';
import { AllocationMatrix } from './AllocationMatrix';
import type { DetailPlanView } from './api';

const days = (n: number) => `${n} day${Math.abs(n) === 1 ? '' : 's'}`;
const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

type Check = DetailPlanView['checks'][number];

/** A phase against the master schedule: when the plan ends it, and how that compares with the master's End. */
export const checkText = (c: Check) => (c.plan_end
  ? `${c.phase} ends ${dm(c.plan_end)} · master ${dm(c.master_end)} · ${c.days_late > 0 ? days(c.days_late) + ' late' : c.days_late < 0 ? days(-c.days_late) + ' early' : 'on time'}`
  : `${c.phase}: nothing planned · master ends ${dm(c.master_end)}`);

export const checkTone = (c: Check): CSSProperties => (!c.plan_end ? { background: 'var(--surface-hover)', color: 'var(--text-muted)' }
  : c.days_late > 0 ? { background: 'var(--hl-red)', color: 'var(--hl-red-fg)' } : { background: 'var(--hl-ok)', color: 'var(--hl-ok-fg)' });

/** The overrun of a member on one day, in words. */
export const overDayText = (o: DetailPlanView['over_day'][number], name: (id: string) => string) =>
  `${name(o.member_id)} has ${hrs(o.planned_h)} planned on ${dm(o.date)}, above the ${hrs(o.allocation_h)} allocation`;

const pill = (tone: CSSProperties): CSSProperties => ({ ...tone, border: 0, cursor: 'pointer', gap: 6, lineHeight: '24px', padding: '0 10px' });

/**
 * Where the plan stands, always in view: each phase against the master schedule, how much is left to assign, and how many
 * problems stand before the push. The problems themselves open from their count, and on their own once {@code checked}
 * (the PIM saved, tried to finish or reviewed the push).
 */
export function PlanSummary({ epic, v, name, checked }: { epic: string; v: DetailPlanView; name: (id: string) => string; checked: boolean }) {
  const saving = useIsMutating() > 0;
  const [showIssues, setShowIssues] = useState(false);
  const [showWarn, setShowWarn] = useState(false);
  const open = v.rows.filter((r) => !r.pushed_at);
  const lines = v.steps.filter((s) => !s.removed && open.some((r) => r.id === s.row_id)).length;
  const unassigned = open.filter((r) => !r.ba_id || (r.steps && !r.bal_id)).length;
  const issues = v.problems.length + v.over_budget.length;
  const issuesOpen = issues > 0 && (checked || showIssues);
  const warnOpen = v.over_day.length > 0 && (checked || showWarn);
  return (
    <section className="card" aria-label="Plan status" style={{ padding: '12px 16px', display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8 }}>
        {v.checks.map((c) => <span key={c.phase} className="status-pill" style={{ ...checkTone(c), lineHeight: '24px', padding: '0 10px' }}>{checkText(c)}</span>)}
        {v.checks.length === 0 && <span className="eyebrow">Save the master schedule to compare the plan with it.</span>}
        <span className="spacer" />
        <span className="eyebrow" aria-live="polite" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          {saving ? 'Saving…' : <><Icon name="check" size={13} color="var(--hl-ok-fg)" />Saved</>}
        </span>
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8, fontSize: 13 }}>
        <span className="heading" style={{ fontWeight: 600 }}>{`${plural(open.length, 'function')} · ${plural(lines, 'sub-task')}`}</span>
        {unassigned > 0 && <span className="status-pill" style={{ background: 'var(--hl-amber)', color: 'var(--hl-amber-fg)', lineHeight: '24px', padding: '0 10px' }}>
          {`${plural(unassigned, 'function')} without a BA or BAL`}</span>}
        <span className="spacer" />
        {issues > 0 && (
          <button type="button" className="status-pill" aria-expanded={issuesOpen} onClick={() => setShowIssues((x) => !x)}
            style={pill({ background: 'var(--hl-red)', color: 'var(--hl-red-fg)' })}>
            <Icon name="circle-alert" size={14} />{`${plural(issues, 'problem')} before the push`}<Icon name={issuesOpen ? 'chevron-up' : 'chevron-down'} size={14} />
          </button>
        )}
        {v.over_day.length > 0 && (
          <button type="button" className="status-pill" aria-expanded={warnOpen} onClick={() => setShowWarn((x) => !x)}
            style={pill({ background: 'var(--hl-amber)', color: 'var(--hl-amber-fg)' })}>
            <Icon name="triangle-alert" size={14} />{`${plural(v.over_day.length, 'day')} above the allocation`}<Icon name={warnOpen ? 'chevron-up' : 'chevron-down'} size={14} />
          </button>
        )}
        {issues === 0 && v.over_day.length === 0 && v.plan.length > 0 && (
          <span className="status-pill" style={{ background: 'var(--hl-ok)', color: 'var(--hl-ok-fg)', lineHeight: '24px', padding: '0 10px' }}>
            <Icon name="circle-check" size={14} />Ready to push</span>
        )}
      </div>
      {issuesOpen && (
        <div role="alert" aria-label="Before the push" style={{ display: 'flex', flexDirection: 'column', gap: 4, padding: '10px 12px', borderRadius: 10, background: 'var(--hl-red)' }}>
          {v.problems.map((p) => <span key={p} style={{ color: 'var(--hl-red-fg)', fontSize: 13 }}>{p}</span>)}
          {v.over_budget.map((o) => <span key={o.member_id + o.phase} style={{ color: 'var(--hl-red-fg)', fontSize: 13 }}>{`${name(o.member_id)} is over budget in ${o.phase}: ${hrs(o.planned_h)} of ${hrs(o.budget_h)}`}</span>)}
          {v.allocated.length > 0 && <div style={{ background: 'var(--surface-card)', borderRadius: 8, padding: '4px 6px' }}><AllocationMatrix epic={epic} rows={v.allocated} name={name} /></div>}
        </div>
      )}
      {warnOpen && (
        <div role="region" aria-label="Over the allocation" style={{ display: 'flex', flexDirection: 'column', gap: 4, padding: '10px 12px', borderRadius: 10, background: 'var(--hl-amber)' }}>
          <span className="eyebrow" style={{ color: 'var(--hl-amber-fg)' }}>Warnings: the push asks to confirm them.</span>
          {v.over_day.map((o) => <span key={o.member_id + o.date} style={{ color: 'var(--hl-amber-fg)', fontSize: 13 }}>{overDayText(o, name)}</span>)}
        </div>
      )}
      {v.stale_phases.length > 0 && (
        <div role="note" style={{ fontSize: 13, color: 'var(--hl-amber-fg)', display: 'flex', gap: 6, alignItems: 'center' }}>
          <Icon name="triangle-alert" size={14} />
          {`The BA effort of ${v.stale_phases.join(', ')} changed since the master schedule was saved: save the master schedule again to compute ${v.stale_phases.length === 1 ? 'its' : 'their'} End.`}
        </div>
      )}
    </section>
  );
}
