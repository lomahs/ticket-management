import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useCan } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button, Icon } from '../../../shared/ds';
import { hrs, ymd } from '../../../shared/format/dates';
import { useToast } from '../../../shared/state/toast';
import { onboardingQuery } from '../../onboarding';
import { usePushPlan, usePushPreview, type DetailPlanView } from './api';
import { overDayText } from './PlanSummary';

/**
 * The push of the plan, for the step's action bar: what stands before it (the problems, then what blocks the push), then Review
 * push, which opens what the push creates and changes in Jira (with the days above an allocation as a warning) and pushes it.
 */
export function PushControls({ epic, v, name, onCheck }: { epic: string; v: DetailPlanView; name: (id: string) => string; onCheck: () => void }) {
  const admin = useCan('planning', 'write');
  const toast = useToast();
  const preview = usePushPreview(epic);
  const push = usePushPlan(epic);
  const [open, setOpen] = useState(false);
  // the sub-tasks to create, and the sub-tasks in Jira changed here
  const lines = v.lines.filter((l) => (l.row_id != null && !l.removed) || (l.issue_key != null && l.pending)).length;
  if (!admin || (!v.rows.some((r) => !r.pushed_at) && lines === 0)) return null;
  const before = [...v.problems, ...v.push_blockers];
  const issues = before.length;
  const blocked = issues > 0 || lines === 0;
  const why = issues > 0 ? `Fix ${issues} problem${issues === 1 ? '' : 's'} to push` : lines === 0 ? 'Nothing to push' : null;
  return (
    <>
      {why && (
        <button type="button" className="row-btn eyebrow" onClick={onCheck} title={before.length ? before.join('\n') : undefined} style={{ color: issues ? 'var(--hl-red-fg)' : undefined, display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <Icon name="circle-alert" size={14} />{why}
        </button>
      )}
      <Button iconBefore="eye" disabled={blocked || preview.isPending} title={why ?? 'What the push creates and changes in Jira'} onClick={() => {
        onCheck();
        preview.mutate(undefined, { onSuccess: () => setOpen(true), onError: (e) => toast(errorMessage(e), true) });
      }}>Review push</Button>
      {open && preview.data && (
        <PushDialog epic={epic} v={v} name={name} creates={preview.data.creates} updates={preview.data.updates} pushing={push.isPending}
          close={() => setOpen(false)}
          push={() => push.mutate(undefined, {
            onSuccess: (r) => { toast(pushed(Object.keys(r.created).length, r.updated.length)); setOpen(false); preview.reset(); },
            onError: (e) => toast(errorMessage(e), true),
          })} />
      )}
    </>
  );
}

/** What a push did, in words; nothing sent to Jira when only the hours per day changed. */
const pushed = (created: number, updated: number) => (created + updated === 0 ? 'Pushed · the hours per day are kept here'
  : `${[created > 0 || updated === 0 ? `Created ${created} sub-tasks` : null, updated > 0 ? `${created > 0 ? 'updated' : 'Updated'} ${updated}` : null]
    .filter(Boolean).join(' · ')} in Jira`);

type Create = Schemas['BatchPreview']['creates'][number];
type Update = Schemas['BatchPreview']['updates'][number];
type Field = Schemas['FieldChange']['field'];

const FIELD: Record<Field, string> = {
  summary: 'Summary', status: 'Status', assignee: 'PIC', reporter: 'Reporter', planned_start: 'Start', planned_end: 'End', estimate: 'Original estimate',
  remaining: 'Remaining',
};

/** A field's value in the review: hours, names, dates as yyyy/MM/dd. */
function value(field: Field, x: string | null | undefined, name: (id: string) => string): string {
  if (x == null || x === '') return '–';
  if (field === 'estimate' || field === 'remaining') return hrs(Number(x));
  if (field === 'assignee' || field === 'reporter') return name(x);
  if (field === 'planned_start' || field === 'planned_end') return ymd(x);
  return x;
}

function PushDialog({ epic, v, name, creates, updates, pushing, push, close }: {
  epic: string; v: DetailPlanView; name: (id: string) => string; creates: Create[]; updates: Update[]; pushing: boolean; push: () => void;
  close: () => void;
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
          <span className="dialog-title">{creates.length + updates.length === 0 ? 'Nothing changes in Jira'
            : [creates.length > 0 && `${creates.length} sub-tasks to create`, updates.length > 0 && `${updates.length} to update`].filter(Boolean).join(' · ')}</span>
          <span className="muted" style={{ fontSize: 12 }}>{`Labels ${labels.length ? labels.join(', ') : '—'} (Setting Phase)`}</span>
        </div>
        {v.over_day.length > 0 && (
          <div role="note" aria-label="Days above the allocation" style={{ display: 'flex', flexDirection: 'column', gap: 4, padding: '10px 12px', borderRadius: 10, background: 'var(--hl-amber)', color: 'var(--hl-amber-fg)', fontSize: 13 }}>
            <b style={{ display: 'flex', alignItems: 'center', gap: 6 }}><Icon name="triangle-alert" size={14} />Some days go above the allocation: the sub-tasks are created with these dates.</b>
            {v.over_day.map((o) => <span key={o.member_id + o.date}>{overDayText(o, name)}</span>)}
          </div>
        )}
        {creates.length + updates.length === 0 && (
          <span style={{ fontSize: 13 }}>The push keeps the hours per day of the sub-tasks in Jira here: Jira has no field for them.</span>
        )}
        {creates.length + updates.length > 0 && (
          <div style={{ overflowY: 'auto', border: '1px solid var(--border-subtle)', borderRadius: 10, minHeight: 0 }}>
            {creates.map((c, i) => (
              <div key={c.ref} style={{ display: 'flex', gap: 10, padding: '6px 12px', borderTop: i ? '1px solid var(--border-subtle)' : undefined, fontSize: 13, paddingLeft: c.subtask ? 28 : 12 }}>
                <span className="eyebrow" style={{ minWidth: 64 }}>{c.subtask ? 'Sub-task' : 'Task'}</span>
                <span className="heading">{c.summary}</span>
              </div>
            ))}
            {updates.length > 0 && (
              <ul aria-label="Changes to sub-tasks in Jira" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                {updates.map((u, i) => (
                  <li key={u.key} aria-label={u.key} style={{ display: 'flex', flexDirection: 'column', gap: 4, padding: '6px 12px', fontSize: 13,
                    borderTop: i || creates.length ? '1px solid var(--border-subtle)' : undefined }}>
                    <span style={{ display: 'flex', gap: 10 }}><span className="key">{u.key}</span><span className="heading">{u.summary}</span></span>
                    <span style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                      {u.changes.map((c) => (
                        <span key={c.field} style={{ padding: '2px 8px', borderRadius: 4, background: 'var(--surface-hover)', fontSize: 12 }}>
                          {`${FIELD[c.field]} ${value(c.field, c.before, name)} → ${value(c.field, c.after, name)}`}
                        </span>
                      ))}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
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
