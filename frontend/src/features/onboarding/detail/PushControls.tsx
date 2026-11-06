import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useCan } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button, Icon } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { onboardingQuery } from '../api';
import { usePushPlan, usePushPreview, type DetailPlanView } from './api';
import { overDayText } from './PlanSummary';

/**
 * The push of the plan, for the step's action bar: what stands before it, then Review push, which opens what the push
 * creates in Jira (with the days above an allocation to confirm) and pushes it.
 */
export function PushControls({ epic, v, name, onCheck }: { epic: string; v: DetailPlanView; name: (id: string) => string; onCheck: () => void }) {
  const admin = useCan('planning', 'write');
  const toast = useToast();
  const preview = usePushPreview(epic);
  const push = usePushPlan(epic);
  const [open, setOpen] = useState(false);
  if (!admin || !v.rows.some((r) => !r.pushed_at)) return null;
  const issues = v.problems.length + v.over_budget.length;
  const blocked = issues > 0 || v.plan.length === 0;
  const allow = v.over_day.length > 0;
  const why = issues > 0 ? `Fix ${issues} problem${issues === 1 ? '' : 's'} to push` : v.plan.length === 0 ? 'Nothing planned yet' : null;
  return (
    <>
      {why && (
        <button type="button" className="row-btn eyebrow" onClick={onCheck} style={{ color: issues ? 'var(--hl-red-fg)' : undefined, display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <Icon name="circle-alert" size={14} />{why}
        </button>
      )}
      <Button iconBefore="eye" disabled={blocked || preview.isPending} title={why ?? 'What the push creates in Jira'} onClick={() => {
        onCheck();
        preview.mutate(allow, { onSuccess: () => setOpen(true), onError: (e) => toast(errorMessage(e), true) });
      }}>Review push</Button>
      {open && preview.data && (
        <PushDialog epic={epic} v={v} name={name} creates={preview.data.creates} pushing={push.isPending} close={() => setOpen(false)}
          push={() => push.mutate(allow, {
            onSuccess: (r) => { toast(`Created ${Object.keys(r.created).length} sub-tasks in Jira`); setOpen(false); preview.reset(); },
            onError: (e) => toast(errorMessage(e), true),
          })} />
      )}
    </>
  );
}

type Create = Schemas['BatchPreview']['creates'][number];

function PushDialog({ epic, v, name, creates, pushing, push, close }: {
  epic: string; v: DetailPlanView; name: (id: string) => string; creates: Create[]; pushing: boolean; push: () => void; close: () => void;
}) {
  const labels = useQuery(onboardingQuery(epic)).data?.labels ?? [];
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape' && !pushing) close(); };
    document.addEventListener('keydown', key);
    return () => document.removeEventListener('keydown', key);
  }, [close, pushing]);
  return (
    <div className="scrim" onClick={() => { if (!pushing) close(); }}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Push review" onClick={(e) => e.stopPropagation()}
        style={{ width: 'min(720px,100%)', maxHeight: 'calc(100vh - 48px)', boxSizing: 'border-box' }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <span className="dialog-title">{`${creates.length} sub-tasks to create`}</span>
          <span className="muted" style={{ fontSize: 12 }}>{`Labels ${labels.length ? labels.join(', ') : '—'} (Setting Phase)`}</span>
        </div>
        {v.over_day.length > 0 && (
          <div role="note" aria-label="Days above the allocation" style={{ display: 'flex', flexDirection: 'column', gap: 4, padding: '10px 12px', borderRadius: 10, background: 'var(--hl-amber)', color: 'var(--hl-amber-fg)', fontSize: 13 }}>
            <b style={{ display: 'flex', alignItems: 'center', gap: 6 }}><Icon name="triangle-alert" size={14} />Some days go above the allocation: the sub-tasks are created with these dates.</b>
            {v.over_day.map((o) => <span key={o.member_id + o.date}>{overDayText(o, name)}</span>)}
          </div>
        )}
        <div style={{ overflowY: 'auto', border: '1px solid var(--border-subtle)', borderRadius: 10, minHeight: 0 }}>
          {creates.map((c, i) => (
            <div key={c.ref} style={{ display: 'flex', gap: 10, padding: '6px 12px', borderTop: i ? '1px solid var(--border-subtle)' : undefined, fontSize: 13, paddingLeft: c.subtask ? 28 : 12 }}>
              <span className="eyebrow" style={{ minWidth: 64 }}>{c.subtask ? 'Sub-task' : 'Task'}</span>
              <span className="heading">{c.summary}</span>
            </div>
          ))}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="eyebrow">Nothing reaches Jira until you push.</span>
          <span className="spacer" />
          <Button appearance="subtle" disabled={pushing} onClick={close}>Cancel</Button>
          <Button iconBefore="upload" disabled={pushing} onClick={push}>{pushing ? 'Pushing…' : 'Push to Jira'}</Button>
        </div>
      </div>
    </div>
  );
}
