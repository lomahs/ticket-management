import type { Schemas } from '../../../shared/api/types';
import { Button, Icon, IconButton, type IconName } from '../../../shared/ds';
import { dm, hrs } from '../../../shared/format/dates';
import { statusOf } from '../../../shared/format/labels';

type Field = Schemas['FieldChange']['field'];

const LABEL: Record<Field, string> = {
  status: 'Status', assignee: 'Assignee', reporter: 'Reporter', planned_start: 'Planned Start', planned_end: 'Planned End',
  estimate: 'Original estimate',
};

/** A raw value of the API as the review shows it. */
function show(field: Field, v: string | null | undefined, name: (id: string) => string): string {
  if (v == null || v === '') return '–';
  if (field === 'status') return statusOf(v).label;
  if (field === 'assignee' || field === 'reporter') return name(v);
  if (field === 'estimate') return hrs(Number(v));
  return `${dm(v)}/${v.slice(0, 4)}`;
}

/**
 * The review of a ticket change: each field with what Jira has now and what it will have, the exact requests, and what Jira
 * itself may still do. Apply sends these changes back, so nothing else is written.
 */
export function ReviewDialog({ review, name, applying, apply, close }: {
  review: Schemas['TicketChangePreview']; name: (id: string) => string; applying: boolean; apply: () => void; close: () => void;
}) {
  const n = review.changes.length;
  const unchanged = (Object.keys(LABEL) as Field[]).filter((f) => !review.changes.some((c) => c.field === f)).map((f) => LABEL[f]);
  const notes: { icon: IconName; text: string }[] = [
    { icon: 'shield-check', text: `Only ${n === 1 ? 'this field is' : `these ${n} fields are`} sent.${unchanged.length ? ` ${unchanged.join(', ')} stay as they are in Jira.` : ''}` },
    { icon: 'refresh-cw', text: 'Jira is read again just before writing. If a “Now in Jira” value has changed since this review, nothing is written.' },
  ];
  if (review.transition) {
    notes.push({ icon: 'git-branch', text: `Status uses the Jira transition “${review.transition}” to ${review.jira_status}. Jira's workflow may fill fields itself, such as Resolution.` });
  }
  if (review.estimate_tracking) {
    notes.push({ icon: 'timer', text: 'The estimate is written through Jira time tracking, which may also change the remaining estimate.' });
  }
  const grid = { display: 'grid', gridTemplateColumns: 'minmax(0,1.1fr) minmax(0,1fr) minmax(0,1fr)', gap: 10, padding: '8px 12px' } as const;
  return (
    <div className="scrim" onClick={applying ? undefined : close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Apply to Jira" onClick={(e) => e.stopPropagation()}
        style={{ width: 'min(640px,100%)', maxHeight: '100%', overflow: 'auto' }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="dialog-title">Apply to Jira</div>
            <div className="eyebrow ellipsis">{review.key} · {review.summary}</div>
          </div>
          <IconButton icon="x" label="Close" size="sm" onClick={close} disabled={applying} />
        </div>
        <div style={{ border: '1px solid var(--border-subtle)', borderRadius: 8, overflow: 'hidden', fontSize: 13 }}>
          <div style={{ ...grid, background: 'var(--surface-hover)', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)' }}>
            <span>Field</span><span>Now in Jira</span><span>After apply</span>
          </div>
          {review.changes.map((c) => (
            <div key={c.field} style={{ ...grid, alignItems: 'start', borderTop: '1px solid var(--border-subtle)' }}>
              <span><b className="heading">{LABEL[c.field]}</b><div className="mono" style={{ fontSize: 11, color: 'var(--text-muted)' }}>{c.jira_field}</div></span>
              <s className="muted">{show(c.field, c.before, name)}</s>
              <b className="heading">{show(c.field, c.after, name)}{c.field === 'status' && review.jira_status ? ` · ${review.jira_status}` : ''}</b>
            </div>
          ))}
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <div className="title" style={{ fontSize: 12 }}>Requests to Jira · {review.requests.length}</div>
          {review.requests.map((r, i) => (
            <div key={i} style={{ padding: '8px 10px', borderRadius: 6, background: 'var(--surface-hover)', font: '12px/1.5 var(--font-mono)', wordBreak: 'break-all' }}>
              <div className="heading" style={{ fontWeight: 600 }}>{r.method} {r.path}</div>
              {r.body && <div className="muted">{r.body}</div>}
            </div>
          ))}
        </div>
        {notes.map((x) => (
          <div key={x.icon} style={{ display: 'flex', gap: 8, alignItems: 'flex-start', padding: '10px 12px', borderRadius: 8, background: 'var(--surface-hover)', fontSize: 12, lineHeight: 1.45 }}>
            <Icon name={x.icon} size={14} style={{ flex: 'none', marginTop: 1 }} />
            <span>{x.text}</span>
          </div>
        ))}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <span className="eyebrow" style={{ flex: 1, minWidth: 200 }}>Each request is recorded in Settings › Jira log.</span>
          <Button appearance="subtle" onClick={close} disabled={applying}>Cancel</Button>
          <Button iconBefore="upload" disabled={applying} onClick={apply}>{applying ? 'Applying…' : `Apply ${n} change${n === 1 ? '' : 's'}`}</Button>
        </div>
      </div>
    </div>
  );
}
