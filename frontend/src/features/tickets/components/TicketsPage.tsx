import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { Fragment, useState, type CSSProperties } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { membersQuery, useAdminJiraWrite } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button, Icon, IconButton, Select, TextField, type Option } from '../../../shared/ds';
import { STATUS, statusOf } from '../../../shared/format/labels';
import { useToast } from '../../../shared/state/toast';
import { useOpenTicket } from '../../../shared/state/ticket';
import { Loading, QueryError } from '../../../shared/ui/states';
import { boardQuery, useApplyBatch, usePreviewBatch } from '../api';
import { downloadCsv, jqlUrl, seconds } from '../model/csv';
import { createsOf, missing, saved, updatesOf, withEdit, type Edits, type Field, type NewRow } from '../model/draft';
import { AddSubtasksDialog, type ParentTask } from './AddSubtasksDialog';
import { CommitDialog } from './CommitDialog';
import { NewTaskDialog } from './NewTaskDialog';

const route = getRouteApi('/app/tickets/$epic');
const GRID = '28px 92px minmax(260px,1fr) 104px 124px 124px 132px 132px 72px 64px';
const FIELD_LABEL: Record<Field, string> = {
  status: 'Status', assignee_id: 'Assignee', reporter_id: 'Reporter', planned_start: 'Start', planned_end: 'End', estimate_h: 'Estimate',
};
const STATUS_OPTIONS: Option[] = (Object.keys(STATUS) as (keyof typeof STATUS)[]).map((k) => ({ value: k, label: STATUS[k].label }));

/** An inline cell input: borderless until hovered or focused, tinted while it holds an edit not yet committed. */
const cell = (edited: boolean, extra?: CSSProperties): CSSProperties => ({
  boxSizing: 'border-box', width: '100%', height: 30, padding: '0 6px', border: '1px solid transparent', borderRadius: 4,
  font: '13px/1 var(--font-text)', color: 'var(--text-heading)', background: edited ? 'var(--surface-tint)' : 'transparent', ...extra,
});

/** The Tasks and sub-tasks of one anken: admins edit and create them as drafts, review the batch against Jira, then commit it. */
export function TicketsPage() {
  const { epic } = route.useParams();
  const q = useQuery(boardQuery(epic));
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  return <Board key={epic} b={q.data} />;
}

function Board({ b }: { b: Schemas['TicketBoard'] }) {
  const { ok: canWrite, off } = useAdminJiraWrite();
  const members = useQuery(membersQuery(b.epic)).data ?? [];
  const people: Option[] = members.filter((m) => m.people).map((m) => ({ value: m.id, label: m.name }));
  const name = (id: string) => members.find((m) => m.id === id)?.name ?? id;
  const email = (id: string | undefined | null) => (id ? members.find((m) => m.id === id)?.email || name(id) : '');
  const open = useOpenTicket();
  const toast = useToast();
  const preview = usePreviewBatch();
  const apply = useApplyBatch();

  const [edits, setEdits] = useState<Edits>({});
  const [news, setNews] = useState<NewRow[]>([]);
  const [sel, setSel] = useState<Set<string>>(new Set());
  const [closed, setClosed] = useState<Set<string>>(new Set());
  const [who, setWho] = useState('all');
  const [text, setText] = useState('');
  const [view, setView] = useState<'all' | 'unsaved'>('all');
  const [dialog, setDialog] = useState<{ kind: 'task' } | { kind: 'sub'; task: ParentTask } | null>(null);
  const [review, setReview] = useState<{ preview: Schemas['BatchPreview']; creates: Schemas['NewTicketIn'][] } | null>(null);

  const rows = b.tasks.flatMap((t) => [t.task, ...t.subtasks]);
  const byKey = Object.fromEntries(rows.map((r) => [r.key, r]));
  const value = (r: Schemas['BoardRow'], f: Field) => edits[r.key]?.[f] ?? saved(r, f);
  const edit = (r: Schemas['BoardRow'], f: Field) => (v: string) => canWrite && setEdits((e) => withEdit(e, r, f, v));
  const setNew = (ref: string, k: keyof NewRow) => (v: string) => setNews((n) => n.map((x) => (x.ref === ref ? { ...x, [k]: v } : x)));
  const removeNew = (ref: string) => setNews((n) => n.filter((x) => x.ref !== ref && x.parent_ref !== ref));

  const needle = text.trim().toLowerCase();
  const matchRow = (r: Schemas['BoardRow']) => (who === 'all' || value(r, 'assignee_id') === who)
    && (!needle || `${r.key} ${r.summary}`.toLowerCase().includes(needle)) && (view === 'all' || !!edits[r.key]);
  const matchNew = (r: NewRow) => (who === 'all' || r.assignee_id === who) && (!needle || r.summary.toLowerCase().includes(needle));
  const toggle = (id: string) => setClosed((c) => { const n = new Set(c); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const pick = (key: string) => setSel((s) => { const n = new Set(s); if (n.has(key)) n.delete(key); else n.add(key); return n; });

  const edited = Object.keys(edits).length;
  const bad = news.filter((r) => missing(r).length > 0);
  const selected = [...sel].filter((k) => byKey[k]);
  const bulk = (f: Field) => (v: string) => v && setEdits((e) => selected.reduce((acc, k) => withEdit(acc, byKey[k], f, v), e));

  const parentLabel = (parent: string) => {
    if (parent.startsWith('<new:')) return `new task ${news.find((n) => n.ref === parent.slice(5, -1))?.summary ?? ''}`;
    if (parent === b.epic) return `${b.epic} · ${b.title}`;
    return `${parent} ${byKey[parent]?.summary ?? ''}`;
  };

  const openReview = () => {
    const creates = createsOf(news);
    preview.mutate({ epic: b.epic, updates: updatesOf(edits), creates }, {
      onSuccess: (p) => {
        if (p.updates.length || p.creates.length) setReview({ preview: p, creates });
        else {
          setEdits({});
          toast('Jira already has these values; nothing to commit');
        }
      },
      onError: (e) => toast(errorMessage(e), true),
    });
  };
  const commit = () => review && apply.mutate({
    epic: b.epic,
    updates: review.preview.updates.map((u) => ({ key: u.key, changes: u.changes.map(({ field, before, after }) => ({ field, before, after })) })),
    creates: review.creates,
  }, {
    onSuccess: (r) => {
      const made = Object.keys(r.created).length;
      toast(`Written to Jira · ${[r.updated.length && `${r.updated.length} updated`, made && `${made} created`].filter(Boolean).join(', ')}`);
      setEdits({});
      setNews([]);
      setSel(new Set());
      setReview(null);
    },
    onError: (e) => {
      toast(errorMessage(e), true); // a 409: a ticket changed in Jira after the review and nothing was written
      setReview(null);
    },
  });

  const exportSelected = () => {
    downloadCsv(`jira-update-${b.epic}.csv`, ['Issue key', 'Summary', 'Status', 'Assignee', 'Reporter', 'Start date', 'End date', 'Original Estimate'],
      selected.map((k) => {
        const r = byKey[k];
        return [r.key, r.summary, statusOf(value(r, 'status')).label, email(value(r, 'assignee_id')), email(value(r, 'reporter_id')),
          value(r, 'planned_start'), value(r, 'planned_end'), seconds(value(r, 'estimate_h'))];
      }));
    toast(`Exported ${selected.length} tickets · in Jira, import with “Update existing work items” mapped by Issue key`);
  };
  const exportNew = () => {
    const ordered = [...news].sort((a, c) => Number(a.subtask) - Number(c.subtask));
    const id = (ref: string) => ordered.findIndex((r) => r.ref === ref) + 1;
    downloadCsv(`jira-new-${b.epic}.csv`, ['Issue Id', 'Issue Type', 'Summary', 'Parent Id', 'Parent', 'Assignee', 'Reporter', 'Start date', 'End date', 'Original Estimate'],
      ordered.map((r) => [id(r.ref), r.subtask ? 'Sub-task' : 'Task', r.summary, r.parent_ref ? id(r.parent_ref) : '', r.subtask ? r.parent_key ?? '' : b.epic,
        email(r.assignee_id), email(r.reporter_id), r.planned_start, r.planned_end, seconds(r.estimate_h)]));
    toast(`Exported ${news.length} new tickets · Jira › Import work items from CSV`);
  };

  const selectStyle = (e: boolean) => cell(e, { cursor: canWrite ? 'pointer' : 'default' });
  const existing = (r: Schemas['BoardRow'], depth: 0 | 1, task?: Schemas['BoardTask']) => {
    const e = edits[r.key] ?? {};
    const was = (f: Field) => (e[f] != null ? `Was ${saved(r, f) || '–'}` : undefined);
    const kids = task ? task.subtasks.length + news.filter((n) => n.parent_key === r.key).length : 0;
    return (
      <div key={r.key} role="row" style={{ display: 'grid', gridTemplateColumns: GRID, gap: 8, alignItems: 'center', padding: '3px 16px', borderTop: '1px solid var(--border-subtle)',
        background: sel.has(r.key) ? 'color-mix(in oklch, var(--surface-tint) 55%, transparent)' : undefined }}>
        <input type="checkbox" aria-label={`Select ${r.key}`} checked={sel.has(r.key)} onChange={() => pick(r.key)} />
        <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <button type="button" className="row-btn key" onClick={() => open(r.key)} title="Open the ticket">{r.key}</button>
          {Object.keys(e).length > 0 && <span title="Edited · not committed" style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--color-accent)' }} />}
        </span>
        <span style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0, paddingLeft: depth ? 22 : 0 }}>
          {task && (
            <IconButton icon={closed.has(r.key) ? 'chevron-right' : 'chevron-down'} label={closed.has(r.key) ? 'Show sub-tasks' : 'Hide sub-tasks'} size="sm" onClick={() => toggle(r.key)} />
          )}
          <span className="ellipsis" title={r.summary} style={{ fontWeight: task ? 600 : 400, color: 'var(--text-heading)' }}>{r.summary}</span>
          {task && <span className="eyebrow" style={{ flex: 'none' }}>{task.phase} · {kids}</span>}
        </span>
        <select aria-label={`${r.key} status`} value={value(r, 'status')} disabled={!canWrite} title={was('status')} onChange={(ev) => edit(r, 'status')(ev.target.value)}
          style={selectStyle(e.status != null)}>
          {STATUS_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
        {(['assignee_id', 'reporter_id'] as const).map((f) => (
          <select key={f} aria-label={`${r.key} ${FIELD_LABEL[f].toLowerCase()}`} value={value(r, f)} disabled={!canWrite} title={was(f)}
            onChange={(ev) => edit(r, f)(ev.target.value)} style={selectStyle(e[f] != null)}>
            {!value(r, f) && <option value="">–</option>}
            {value(r, f) && !people.some((p) => p.value === value(r, f)) && <option value={value(r, f)}>{name(value(r, f))}</option>}
            {people.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
          </select>
        ))}
        {(['planned_start', 'planned_end'] as const).map((f) => (
          <input key={f} type="date" aria-label={`${r.key} ${FIELD_LABEL[f].toLowerCase()}`} value={value(r, f)} disabled={!canWrite} title={was(f)}
            onChange={(ev) => edit(r, f)(ev.target.value)} style={cell(e[f] != null)} />
        ))}
        <input type="number" min={0} step={0.5} aria-label={`${r.key} estimate`} value={value(r, 'estimate_h')} disabled={!canWrite} title={was('estimate_h')}
          onChange={(ev) => edit(r, 'estimate_h')(ev.target.value)} style={cell(e.estimate_h != null, { textAlign: 'right' })} />
        <span style={{ display: 'flex', justifyContent: 'flex-end' }}>
          {task && canWrite && (
            <IconButton icon="plus" label={`Add sub-tasks to ${r.key}`} size="sm"
              onClick={() => setDialog({ kind: 'sub', task: { key: r.key, summary: r.summary, workflow: task.workflow } })} />
          )}
        </span>
      </div>
    );
  };
  const draft = (r: NewRow, depth: 0 | 1) => {
    const need = missing(r);
    const inputs = (k: keyof NewRow) => setNew(r.ref, k);
    return (
      <div key={r.ref} role="row" style={{ display: 'grid', gridTemplateColumns: GRID, gap: 8, alignItems: 'center', padding: '3px 16px', borderTop: '1px solid var(--border-subtle)',
        background: 'color-mix(in oklch, var(--surface-tint) 30%, transparent)' }}>
        <span />
        <span title="Draft · gets a Jira key on commit" style={{ font: '700 10px/18px var(--font-text)', padding: '0 6px', borderRadius: 4, background: 'var(--surface-tint-strong)', justifySelf: 'start' }}>NEW</span>
        <span style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0, paddingLeft: depth ? 22 : 0 }}>
          <input aria-label="Summary" value={r.summary} placeholder="Summary" onChange={(e) => inputs('summary')(e.target.value)}
            style={cell(true, { flex: 1, fontWeight: r.subtask ? 400 : 600, borderColor: need.includes('summary') ? 'var(--hl-red-fg)' : 'transparent' })} />
          {need.length > 0 && <span title={`Fill in: ${need.join(', ')}`}><Icon name="circle-alert" size={14} style={{ color: 'var(--hl-red-fg)' }} /></span>}
        </span>
        <span className="eyebrow">{statusOf('N').label}</span>
        {(['assignee_id', 'reporter_id'] as const).map((k) => (
          <select key={k} aria-label={`New ${FIELD_LABEL[k].toLowerCase()}`} value={r[k]} onChange={(e) => inputs(k)(e.target.value)}
            style={selectStyle(true)}>
            <option value="">{k === 'reporter_id' ? 'Default' : '–'}</option>
            {people.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
          </select>
        ))}
        {(['planned_start', 'planned_end'] as const).map((k) => (
          <input key={k} type="date" aria-label={`New ${FIELD_LABEL[k].toLowerCase()}`} value={r[k]} onChange={(e) => inputs(k)(e.target.value)} style={cell(true)} />
        ))}
        <input type="number" min={0} step={0.5} aria-label="New estimate" value={r.estimate_h} onChange={(e) => inputs('estimate_h')(e.target.value)}
          style={cell(true, { textAlign: 'right' })} />
        <span style={{ display: 'flex', justifyContent: 'flex-end' }}>
          {!r.subtask && (
            <IconButton icon="plus" label="Add sub-tasks to the new task" size="sm"
              onClick={() => setDialog({ kind: 'sub', task: { ref: r.ref, summary: r.summary || 'New task', workflow: false } })} />
          )}
          <IconButton icon="trash-2" label="Remove draft" size="sm" onClick={() => removeNew(r.ref)} />
        </span>
      </div>
    );
  };

  const counts = [news.length && `${news.length} new`, edited && `${edited} updated`].filter(Boolean).join(' · ');
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, width: '100%' }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, flexWrap: 'wrap' }}>
        <div style={{ flex: 1, minWidth: 240 }}>
          <div className="eyebrow">{b.epic} · {b.title} · {b.tasks.length} tasks · {rows.length - b.tasks.length} sub-tasks</div>
          <h1 className="h1" style={{ marginTop: 2 }}>Tickets</h1>
        </div>
        {canWrite && <Button appearance="secondary" iconBefore="plus" onClick={() => setDialog({ kind: 'task' })}>New tasks</Button>}
      </div>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, flexWrap: 'wrap' }}>
        <Select label="Assignee" value={who} style={{ width: 200 }} options={[{ value: 'all', label: 'Everyone' }, ...people]} onChange={setWho} />
        <TextField label="Search" iconBefore="search" placeholder="Key or summary" value={text} style={{ width: 260 }} onChange={setText} />
        <div className="seg" role="group" aria-label="Show" style={{ marginBottom: 4 }}>
          {([['all', 'All'], ['unsaved', `Unsaved · ${edited + news.length}`]] as const).map(([k, label]) => (
            <button key={k} type="button" aria-pressed={view === k} onClick={() => setView(k)}
              style={{ background: view === k ? 'var(--button-primary-bg)' : 'transparent', color: view === k ? 'var(--button-primary-fg)' : 'var(--text-heading)' }}>{label}</button>
          ))}
        </div>
        <span className="eyebrow" style={{ flex: 1, minWidth: 200, marginBottom: 8 }}>
          {canWrite ? 'Edit in place. Nothing reaches Jira until you review and commit.' : `${off}. You can still select tickets to open them in Jira or export them.`}
        </span>
      </div>
      {selected.length > 0 && (
        <div role="toolbar" aria-label="Selected tickets" style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', padding: '8px 12px', borderRadius: 10, background: 'var(--surface-tint)' }}>
          <b>{selected.length} selected</b>
          {canWrite && (
            <>
              {([['status', 'Set status…', STATUS_OPTIONS], ['assignee_id', 'Set assignee…', people], ['reporter_id', 'Set reporter…', people]] as const).map(([f, label, opts]) => (
                <select key={f} aria-label={label} value="" onChange={(e) => bulk(f)(e.target.value)} style={{ height: 32, padding: '0 8px', borderRadius: 4, border: '1px solid var(--border-subtle)', background: 'var(--surface-card)' }}>
                  <option value="">{label}</option>
                  {opts.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select>
              ))}
            </>
          )}
          <span className="spacer" />
          {b.site && (
            <a href={jqlUrl(b.site, selected)} target="_blank" rel="noopener noreferrer" title="Opens these tickets in Jira, ready for Jira's bulk change"
              style={{ display: 'inline-flex', alignItems: 'center', gap: 6, height: 32, padding: '0 12px', borderRadius: 999, border: '1px solid var(--border-strong)', background: 'var(--surface-card)', font: '600 12px/1 var(--font-text)', color: 'var(--text-heading)' }}>
              <Icon name="external-link" size={13} />Open in Jira
            </a>
          )}
          <Button appearance="secondary" size="sm" iconBefore="download" onClick={exportSelected}>Export CSV</Button>
          <Button appearance="subtle" size="sm" onClick={() => setSel(new Set())}>Clear selection</Button>
        </div>
      )}
      <section className="card card-clip" aria-label="Tickets">
        <div style={{ overflowX: 'auto' }}>
          <div role="table" style={{ minWidth: 1200 }}>
            <div role="row" style={{ display: 'grid', gridTemplateColumns: GRID, gap: 8, padding: '8px 16px', background: 'var(--surface-hover)', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)' }}>
              <span /><span>Key</span><span>Summary</span><span>Status</span><span>Assignee</span><span>Reporter</span><span>Start</span><span>End</span><span>Est. (h)</span><span />
            </div>
            {b.tasks.map((t) => {
              const subs = t.subtasks.filter(matchRow);
              const added = news.filter((n) => n.parent_key === t.task.key && matchNew(n));
              if (!matchRow(t.task) && !subs.length && !added.length) return null;
              return (
                <Fragment key={t.task.key}>
                  {existing(t.task, 0, t)}
                  {!closed.has(t.task.key) && subs.map((s) => existing(s, 1))}
                  {!closed.has(t.task.key) && added.map((n) => draft(n, 1))}
                </Fragment>
              );
            })}
            {news.filter((n) => !n.subtask).map((n) => (
              <Fragment key={n.ref}>
                {draft(n, 0)}
                {news.filter((s) => s.parent_ref === n.ref).map((s) => draft(s, 1))}
              </Fragment>
            ))}
            {!b.tasks.length && !news.length && <div className="empty-row">This anken has no Tasks in Jira yet.</div>}
          </div>
        </div>
      </section>
      {canWrite && (
        <div style={{ position: 'sticky', bottom: 0, zIndex: 6, display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', padding: '12px 16px', border: '1px solid var(--border-subtle)', borderRadius: 12, background: 'var(--surface-card)', boxShadow: '0 -4px 16px rgba(9,30,66,0.08)' }}>
          <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-heading)' }}>{counts ? `Not committed · ${counts}` : 'No pending changes'}</span>
          {bad.length > 0 && <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)' }}>{bad.length} new row{bad.length === 1 ? ' needs' : 's need'} a summary or assignee</span>}
          <span className="spacer" />
          {counts && <Button appearance="subtle" onClick={() => { setEdits({}); setNews([]); }}>Discard all</Button>}
          {news.length > 0 && <Button appearance="secondary" iconBefore="download" onClick={exportNew}>Export new as CSV</Button>}
          <Button iconBefore="eye" disabled={!counts || bad.length > 0 || preview.isPending} onClick={openReview}>
            {preview.isPending ? 'Reading Jira…' : 'Review & commit'}
          </Button>
        </div>
      )}
      {dialog?.kind === 'task' && (
        <NewTaskDialog templates={b.templates.map((t) => ({ ...t, exists: t.exists || news.some((n) => !n.subtask && n.summary === t.match) }))} people={people}
          add={(r) => setNews((n) => [...n, ...r])} close={() => setDialog(null)} />
      )}
      {dialog?.kind === 'sub' && (
        <AddSubtasksDialog epic={b.epic} task={dialog.task} people={people} members={members}
          add={(r) => setNews((n) => [...n, ...r])} close={() => setDialog(null)} />
      )}
      {review && (
        <CommitDialog preview={review.preview} parentLabel={parentLabel} name={name} committing={apply.isPending} commit={commit} close={() => setReview(null)} />
      )}
    </div>
  );
}

