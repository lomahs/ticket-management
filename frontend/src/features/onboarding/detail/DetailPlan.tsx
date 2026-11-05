import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useCan } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button } from '../../../shared/ds';
import { dm, hrs } from '../../../shared/format/dates';
import { useToast } from '../../../shared/state/toast';
import { Loading, QueryError } from '../../../shared/ui/states';
import { AddRowDialog } from './AddRowDialog';
import { AllocationMatrix } from './AllocationMatrix';
import { EffortDays } from './EffortDays';
import { StepsTable } from './StepsTable';
import { detailPlanQuery,useAutoAllocate, usePushPlan, usePushPreview, useSaveDraft, type DetailPlanView, type DraftIn } from './api';

/** A person who can be given a row: a member of the anken, with their role. */
export interface Person { id: string; name: string; role?: string | null }

/**
 * Who does each row and the dates that follow, checked against the master schedule; then the push to Jira.
 *
 * @param checked show what stands before the push (problems, budgets, days over the allocation): once the PIM saved,
 *                tried to finish or reviewed the push, not on the first visit
 */
export function DetailPlan({ epic, people, checked = true, onCheck }: { epic: string; people: Person[]; checked?: boolean; onCheck?: () => void }) {
  const q = useQuery(detailPlanQuery(epic));
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  return <Plan epic={epic} v={q.data} people={people} checked={checked} onCheck={onCheck} />;
}

const days = (n: number) => `${n} day${Math.abs(n) === 1 ? '' : 's'}`;
type Row = Schemas['EstimationRow'];

function Plan({ epic, v, people, checked, onCheck }: { epic: string; v: DetailPlanView; people: Person[]; checked: boolean; onCheck?: () => void }) {
  const admin = useCan('planning', 'write');
  const toast = useToast();
  const name = (id: string) => people.find((p) => p.id === id)?.name ?? id;
  const [adding, setAdding] = useState<'function' | 'task' | null>(null);
  const save = useSaveDraft(epic);
  const preview = usePushPreview(epic);
  const push = usePushPlan(epic);
  const auto = useAutoAllocate(epic);
  const open = v.rows.filter((r) => !r.pushed_at);
  const draft = (p: Partial<DraftIn>) => save.mutate({
    order: open.map((r) => r.id),
    assigns: open.map((r) => ({ row_id: r.id, ba_id: r.ba_id ?? null, bal_id: r.bal_id ?? null, jm_id: r.jm_id ?? null })), ...p,
  }, { onError: (e) => toast(errorMessage(e), true) });
  const assign = (r: Row, slot: 'ba_id' | 'bal_id' | 'jm_id', id: string | null) => draft({
    assigns: open.map((x) => { const y = x.id === r.id ? { ...x, [slot]: id } : x; return { row_id: y.id, ba_id: y.ba_id ?? null, bal_id: y.bal_id ?? null, jm_id: y.jm_id ?? null }; }),
  });
  const move = (i: number, d: -1 | 1) => {
    const ids = open.map((r) => r.id);
    [ids[i], ids[i + d]] = [ids[i + d], ids[i]];
    draft({ order: ids });
  };
  const blocked = v.problems.length > 0 || v.over_budget.length > 0 || v.plan.length === 0;
  const overDay = (o: DetailPlanView['over_day'][number]) => `${name(o.member_id)} has ${hrs(o.planned_h)} planned on ${dm(o.date)}, above the ${hrs(o.allocation_h)} allocation`;
  const [confirming, setConfirming] = useState(false);
  const allow = v.over_day.length > 0;
  const review = () => preview.mutate(allow, { onError: (e) => toast(errorMessage(e), true) });
  return (
    <>
      {checked && (v.problems.length > 0 || v.over_budget.length > 0) && (
        <section className="card" aria-label="Before the push" role="alert" style={{ padding: '12px 16px', display: 'flex', flexDirection: 'column', gap: 4 }}>
          {v.problems.map((p) => <span key={p} style={{ color: 'var(--hl-red-fg)', fontSize: 13 }}>{p}</span>)}
          {v.over_budget.map((o) => <span key={o.member_id + o.phase} style={{ color: 'var(--hl-red-fg)', fontSize: 13 }}>{`${name(o.member_id)} is over budget in ${o.phase}: ${hrs(o.planned_h)} of ${hrs(o.budget_h)}`}</span>)}
          {v.allocated.length > 0 && <AllocationMatrix epic={epic} rows={v.allocated} name={name} />}
        </section>
      )}
      {checked && v.over_day.length > 0 && (
        <section className="card" aria-label="Over the allocation" style={{ padding: '12px 16px', display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span className="eyebrow">Warnings: the push asks to confirm them.</span>
          {v.over_day.map((o) => <span key={o.member_id + o.date} style={{ color: 'var(--hl-amber-fg)', fontSize: 13 }}>{overDay(o)}</span>)}
        </section>
      )}
      {v.checks.length > 0 && (
        <section className="card" aria-label="Against the master schedule" style={{ padding: '12px 16px', display: 'flex', gap: 16, flexWrap: 'wrap', fontSize: 13 }}>
          {v.checks.map((c) => (
            <span key={c.phase} style={{ fontWeight: 600, color: c.days_late > 0 ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>
              {c.plan_end ? `${c.phase} ends ${dm(c.plan_end)} · master ${dm(c.master_end)} · ${c.days_late > 0 ? days(c.days_late) + ' late' : c.days_late < 0 ? days(-c.days_late) + ' early' : 'on time'}`
                : `${c.phase}: nothing planned · master ends ${dm(c.master_end)}`}
            </span>
          ))}
        </section>
      )}

      {v.stale_phases.length > 0 && (
        <section className="card" aria-label="Master schedule out of date" style={{ padding: '12px 16px', fontSize: 13, color: 'var(--hl-amber-fg)' }}>
          {`The BA effort of ${v.stale_phases.join(', ')} changed since the master schedule was saved: save the master schedule again to compute ${v.stale_phases.length === 1 ? 'its' : 'their'} End.`}
        </section>
      )}

      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <span className="title">Sub-tasks</span>
        <span className="eyebrow">One line per sub-task, named by Settings › Name rules; who carries its hours follows Settings › Step weights.</span>
        <span className="spacer" />
        {admin && open.length > 0 && (
          <Button appearance="secondary" size="sm" disabled={auto.isPending || save.isPending}
            title="Fills the empty BA / BAL / JM of each function from the phase's team (JMs from the anken), keeping members within their hours in the phase, phases on time and work evenly shared. People already picked and lines set by hand stay."
            onClick={() => auto.mutate(undefined, { onSuccess: () => toast('Empty slots allocated'), onError: (e) => toast(errorMessage(e), true) })}>
            Auto allocate
          </Button>
        )}
        {admin && <Button appearance="secondary" size="sm" iconBefore="plus" disabled={v.tasks.length === 0} onClick={() => setAdding('function')}>Add function</Button>}
        {admin && <Button appearance="secondary" size="sm" iconBefore="plus" disabled={v.tasks.length === 0} onClick={() => setAdding('task')}>Add task</Button>}
      </div>
      <StepsTable epic={epic} v={v} people={people} admin={admin} assign={assign} move={move} />
      <EffortDays v={v} name={name} />
      {adding && <AddRowDialog epic={epic} kind={adding} tasks={v.tasks} close={() => setAdding(null)} />}

      {admin && (
        <div style={{ display: 'flex', gap: 8 }}>
          <Button disabled={blocked || preview.isPending} onClick={() => { onCheck?.(); if (allow) setConfirming(true); else review(); }}>Review push</Button>
        </div>
      )}
      {confirming && (
        <div className="scrim" onClick={() => setConfirming(false)}>
          <div className="dialog" role="dialog" aria-modal="true" aria-label="Confirm the push" onClick={(e) => e.stopPropagation()} style={{ width: 'min(520px,100%)' }}>
            <span className="dialog-title">Some days go above the allocation</span>
            {v.over_day.map((o) => <span key={o.member_id + o.date} style={{ fontSize: 13, color: 'var(--hl-amber-fg)' }}>{overDay(o)}</span>)}
            <span className="eyebrow">The sub-tasks are created with these dates. Continue?</span>
            <div style={{ display: 'flex', gap: 8 }}>
              <span className="spacer" />
              <Button appearance="subtle" onClick={() => setConfirming(false)}>Cancel</Button>
              <Button onClick={() => { setConfirming(false); review(); }}>Continue</Button>
            </div>
          </div>
        </div>
      )}
      {preview.data && (
        <section className="card card-clip" aria-label="Push review">
          <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)', display: 'flex', alignItems: 'center', gap: 10 }}>
            <span className="title">{`${preview.data.creates.length} sub-tasks to create`}</span>
            <span className="spacer" />
            <Button appearance="subtle" size="sm" onClick={() => preview.reset()}>Cancel</Button>
            <Button size="sm" iconBefore="upload" disabled={push.isPending} onClick={() => push.mutate(allow, {
              onSuccess: (r) => { toast(`Created ${Object.keys(r.created).length} sub-tasks in Jira`); preview.reset(); },
              onError: (e) => toast(errorMessage(e), true),
            })}>Push to Jira</Button>
          </div>
          {preview.data.creates.map((c) => (
            <div key={c.ref} style={{ display: 'flex', gap: 10, padding: '6px 16px', borderTop: '1px solid var(--border-subtle)', fontSize: 13, paddingLeft: c.subtask ? 32 : 16 }}>
              <span className="eyebrow" style={{ minWidth: 70 }}>{c.subtask ? 'Sub-task' : 'Task'}</span>
              <span className="heading">{c.summary}</span>
            </div>
          ))}
        </section>
      )}
    </>
  );
}
