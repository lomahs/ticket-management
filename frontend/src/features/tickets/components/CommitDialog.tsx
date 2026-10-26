import type { Schemas } from '../../../shared/api/types';
import { Button, Icon, IconButton } from '../../../shared/ds';
import { dm, hrs } from '../../../shared/format/dates';
import { statusOf } from '../../../shared/format/labels';

type Field = Schemas['FieldChange']['field'];
const LABEL: Record<Field, string> = {
  status: 'Status', assignee: 'Assignee', reporter: 'Reporter', planned_start: 'Start', planned_end: 'End', estimate: 'Estimate',
};

function show(field: Field, v: string | null | undefined, name: (id: string) => string): string {
  if (v == null || v === '') return '–';
  if (field === 'status') return statusOf(v).label;
  if (field === 'assignee' || field === 'reporter') return name(v);
  if (field === 'estimate') return hrs(Number(v));
  return dm(v);
}

function Request({ r }: { r: Schemas['JiraRequest'] }) {
  return (
    <div style={{ padding: '6px 8px', borderRadius: 6, background: 'var(--surface-hover)', font: '11px/1.5 var(--font-mono)', wordBreak: 'break-all' }}>
      <div className="heading" style={{ fontWeight: 600 }}>{r.method} {r.path}</div>
      {r.body && <div className="muted">{r.body}</div>}
    </div>
  );
}

/**
 * The review of a batch: new tickets and changed tickets with each field's value in Jira now and after, and every request
 * that will be sent. Commit sends exactly these.
 */
export function CommitDialog({ preview, parentLabel, name, committing, commit, close }: {
  preview: Schemas['BatchPreview']; parentLabel: (parent: string) => string; name: (id: string) => string;
  committing: boolean; commit: () => void; close: () => void;
}) {
  const requests = preview.creates.length + preview.updates.reduce((n, u) => n + u.requests.length, 0);
  const row = { display: 'grid', gridTemplateColumns: '84px minmax(0,1fr)', gap: 10, alignItems: 'start', padding: '8px 12px', borderTop: '1px solid var(--border-subtle)' } as const;
  return (
    <div className="scrim" onClick={committing ? undefined : close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Commit to Jira" onClick={(e) => e.stopPropagation()}
        style={{ width: 'min(820px,100%)', maxHeight: '100%', overflow: 'auto' }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
          <div style={{ flex: 1 }}>
            <div className="dialog-title">Commit to Jira</div>
            <div className="eyebrow">
              {[preview.creates.length && `${preview.creates.length} new`, preview.updates.length && `${preview.updates.length} updated`].filter(Boolean).join(' · ')}
              {' · '}{requests} request{requests === 1 ? '' : 's'}
            </div>
          </div>
          <IconButton icon="x" label="Close" size="sm" onClick={close} disabled={committing} />
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start', padding: '10px 12px', borderRadius: 8, background: 'var(--surface-hover)', fontSize: 12, lineHeight: 1.45 }}>
          <Icon name="shield-check" size={14} style={{ flex: 'none', marginTop: 1 }} />
          <span>Only the fields shown are sent; everything else on these tickets stays as it is in Jira. Jira is read again just before
            writing: if a ticket changed since this review, nothing at all is written.</span>
        </div>
        {preview.creates.length > 0 && (
          <section aria-label="New tickets" style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            <div className="title" style={{ fontSize: 12 }}>New tickets · {preview.creates.length}</div>
            <div style={{ border: '1px solid var(--border-subtle)', borderRadius: 8, overflow: 'hidden', fontSize: 13 }}>
              {preview.creates.map((c) => (
                <div key={c.ref} style={row}>
                  <span className="mono heading" style={{ fontSize: 12, fontWeight: 600 }}>New</span>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0 }}>
                    <span className="heading ellipsis">{c.summary}</span>
                    <span className="eyebrow">{c.subtask ? 'Sub-task of' : 'Task in'} {parentLabel(c.parent)}</span>
                    <Request r={c.request} />
                  </div>
                </div>
              ))}
            </div>
          </section>
        )}
        {preview.updates.length > 0 && (
          <section aria-label="Updates" style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            <div className="title" style={{ fontSize: 12 }}>Updates · {preview.updates.length}</div>
            <div style={{ border: '1px solid var(--border-subtle)', borderRadius: 8, overflow: 'hidden', fontSize: 13 }}>
              {preview.updates.map((u) => (
                <div key={u.key} style={row}>
                  <span className="key">{u.key}</span>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0 }}>
                    <span className="heading ellipsis">{u.summary}</span>
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                      {u.changes.map((c) => (
                        <span key={c.field} style={{ display: 'inline-flex', gap: 4, padding: '2px 8px', borderRadius: 4, background: 'var(--surface-hover)', fontSize: 12 }}>
                          {LABEL[c.field]} <s className="muted">{show(c.field, c.before, name)}</s> → <b>{show(c.field, c.after, name)}{c.field === 'status' && u.jira_status ? ` · ${u.jira_status}` : ''}</b>
                        </span>
                      ))}
                    </div>
                    {u.requests.map((r, i) => <Request key={i} r={r} />)}
                  </div>
                </div>
              ))}
            </div>
          </section>
        )}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <span className="eyebrow" style={{ flex: 1, minWidth: 200 }}>New tickets get Jira keys after commit. Each request is recorded in Settings › Jira log.</span>
          <Button appearance="subtle" onClick={close} disabled={committing}>Cancel</Button>
          <Button iconBefore="upload" disabled={committing} onClick={commit}>{committing ? 'Committing…' : `Commit ${requests} request${requests === 1 ? '' : 's'}`}</Button>
        </div>
      </div>
    </div>
  );
}
