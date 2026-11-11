import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { useRef, useState, type CSSProperties, type MouseEvent } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { membersQuery, useAdminJiraWrite } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button, DateField, Icon, IconButton, Select, TextField, type Option } from '../../../shared/ds';
import { downloadCsv } from '../../../shared/format/csv';
import { STATUS, statusOf } from '../../../shared/format/labels';
import { useToast } from '../../../shared/state/toast';
import { useOpenTicket } from '../../../shared/state/ticket';
import { Avatar, av } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { boardQuery, useApplyBatch, usePreviewBatch } from '../api';
import { exportRows, importCsv, TEMPLATE, type ImportError } from '../model/csv';
import { createsOf, missing, saved, updatesOf, withEdit, type Edits, type Field, type NewRow } from '../model/draft';
import { jqlUrl } from '../model/jql';
import { clickSort, itemsOf, parseSort, sortItems, sortString, valueOf, type Item, type SortBy, type SortKey } from '../model/list';
import { AddSubtasksDialog, type ParentTask } from './AddSubtasksDialog';
import { CommitDialog } from './CommitDialog';
import { NewTaskDialog } from './NewTaskDialog';

const route = getRouteApi('/app/tickets/$epic');
const GRID = '20px 96px minmax(180px,1fr) 120px 92px 124px 124px 116px 116px 48px 40px';
const FIELD_LABEL: Record<Field, string> = {
  summary: 'Summary', status: 'Status', assignee_id: 'Assignee', reporter_id: 'Reporter', planned_start: 'Start', planned_end: 'End', estimate_h: 'Estimate',
};
const STATUS_OPTIONS: Option[] = (Object.keys(STATUS) as (keyof typeof STATUS)[]).map((k) => ({ value: k, label: STATUS[k].label }));
const TYPE_OPTIONS: Option[] = [{ value: 'sub', label: 'Sub-tasks' }, { value: 'task', label: 'Tasks' }, { value: 'all', label: 'Tasks and sub-tasks' }];
const SORT_LABEL: Record<SortKey, string> = { key: 'Key', assignee: 'Assignee', reporter: 'Reporter', start: 'Start', end: 'End' };

/** An inline cell input: borderless until hovered or focused, amber while it holds an edit not yet committed. */
const cell = (edited: boolean, extra?: CSSProperties): CSSProperties => ({
  boxSizing: 'border-box', width: '100%', height: 30, padding: '0 6px', border: '1px solid transparent', borderRadius: 4,
  font: '13px/1 var(--font-text)', color: 'var(--text-heading)', background: edited ? 'var(--hl-amber)' : 'transparent', ...extra,
});
/** A status as a lozenge, the way Jira lists it. */
const lozenge = (s: string, edited: boolean, canWrite: boolean): CSSProperties => {
  const v = statusOf(s).v;
  return {
    height: 22, padding: '0 6px', border: 0, borderRadius: 4, font: '700 11px/22px var(--font-text)', textTransform: 'uppercase',
    background: `var(--st-${v}-bg)`, color: v === 'nys' ? 'var(--text-muted)' : `var(--st-${v})`, cursor: canWrite ? 'pointer' : 'default',
    outline: edited ? '2px solid var(--hl-amber-fg)' : undefined, outlineOffset: 1,
  };
};
const tag = (bg: string, fg: string): CSSProperties => ({ flex: 'none', font: '700 10px/16px var(--font-text)', padding: '0 5px', borderRadius: 3, background: bg, color: fg });

/**
 * The Tasks and sub-tasks of one anken as one flat list, filtered and sorted like Jira's issue list: admins edit and create
 * them as drafts (by hand or from a CSV of the app's template), review the batch against Jira, then commit it.
 */
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
  const member = (id: string) => members.find((m) => m.id === id);
  const name = (id: string) => member(id)?.name ?? id;
  const email = (id: string) => member(id)?.email || name(id);
  const open = useOpenTicket();
  const toast = useToast();
  const preview = usePreviewBatch();
  const apply = useApplyBatch();
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const setSearch = (patch: Partial<typeof search>) => navigate({ search: (p) => ({ ...p, ...patch }), replace: true });
  const file = useRef<HTMLInputElement>(null);
  const qc = useQueryClient();

  const [edits, setEdits] = useState<Edits>({});
  const [news, setNews] = useState<NewRow[]>([]);
  const [sel, setSel] = useState<Set<string>>(new Set());
  const [dialog, setDialog] = useState<{ kind: 'task' } | { kind: 'pick' } | { kind: 'sub'; task: ParentTask } | { kind: 'errors' } | null>(null);
  const [creating, setCreating] = useState(false);
  const [skipped, setSkipped] = useState<ImportError[]>([]);
  const [review, setReview] = useState<{ preview: Schemas['BatchPreview']; creates: Schemas['NewTicketIn'][] } | null>(null);

  const all = itemsOf(b, news);
  const byId = Object.fromEntries(all.map((it) => [it.id, it]));
  const rows = Object.fromEntries(all.flatMap((it) => (it.row ? [[it.id, it.row]] : [])));
  const sort = parseSort(search.sort);
  const type = search.type ?? 'sub';
  const needle = (search.q ?? '').trim().toLowerCase();
  const match = (it: Item) => {
    const changed = !!it.draft || !!edits[it.id];
    if (search.changed ? !changed : type !== 'all' && it.task !== (type === 'task')) return false;
    if (search.parent && it.parent !== search.parent && it.id !== search.parent) return false;
    if (search.status && valueOf(it, edits, 'status') !== search.status) return false;
    if (search.who && valueOf(it, edits, 'assignee_id') !== search.who) return false;
    return !needle || `${it.row ? it.id : ''} ${valueOf(it, edits, 'summary')}`.toLowerCase().includes(needle);
  };
  const shown = sortItems(all.filter(match), sort, name);
  const filtered = !!(search.q || search.type || search.parent || search.status || search.who || search.changed);

  const edited = Object.keys(edits).length;
  const bad = news.filter((r) => missing(r).length > 0);
  const selected = all.filter((it) => sel.has(it.id));
  const pick = (id: string) => setSel((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const allShown = shown.length > 0 && shown.every((it) => sel.has(it.id));
  const pickShown = () => setSel((s) => {
    const n = new Set(s);
    shown.forEach((it) => (allShown ? n.delete(it.id) : n.add(it.id)));
    return n;
  });
  const bulk = (f: Field) => (v: string) => v && setEdits((e) => selected.reduce((acc, it) => (it.row ? withEdit(acc, it.row, f, v) : acc), e));
  const tasks = all.filter((it) => it.task);
  const taskLabel = (it: Item) => `${it.row ? it.id : 'New'} · ${valueOf(it, edits, 'summary') || 'New task'}`;

  const parentLabel = (parent: string) => {
    if (parent.startsWith('<new:')) return `new task ${news.find((n) => n.ref === parent.slice(5, -1))?.summary ?? ''}`;
    if (parent === b.epic) return `${b.epic} · ${b.title}`;
    return `${parent} ${rows[parent]?.summary ?? ''}`;
  };
  const setNew = (ref: string, k: keyof NewRow, v: string) => setNews((n) => n.map((x) => (x.ref === ref ? { ...x, [k]: v } : x)));
  const removeNew = (ref: string) => setNews((n) => n.filter((x) => x.ref !== ref && x.parent_ref !== ref));
  const discard = () => { setEdits({}); setNews([]); setSkipped([]); };

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
  // The review closes at once and the commit runs in the background (useApplyBatch reports how it ended); the drafts stay until
  // it succeeds, so a refused commit (a 409: a ticket changed in Jira after the review, nothing written) can be reviewed again.
  const commit = () => {
    if (!review) return;
    setReview(null);
    apply.mutate({ epic: b.epic, preview: review.preview, creates: review.creates }, {
      onSuccess: () => {
        discard();
        setSel(new Set());
      },
    });
  };

  const exportSelected = () => {
    downloadCsv(`tickets-${b.epic}.csv`, TEMPLATE, exportRows(shown.filter((it) => sel.has(it.id)).concat(selected.filter((it) => !shown.includes(it))), edits, b.epic, email));
    toast(`Exported ${selected.length} tickets · edit the file, then bring it back with Import CSV`);
  };
  const importFile = async (f: File) => {
    // the members may still be loading when a file is picked right away
    const known = await qc.ensureQueryData(membersQuery(b.epic));
    const r = importCsv(await f.text(), { epic: b.epic, rows, tasks: new Set(tasks.flatMap((it) => (it.row ? [it.id] : []))), members: known }, edits);
    if (!r.ok) {
      toast(`${f.name}: ${r.message}`, true);
      return;
    }
    setEdits(r.edits);
    setNews((n) => [...n, ...r.news]);
    setSkipped(r.errors);
    if (r.updated.length || r.created) setSearch({ changed: true });
    const counts = [r.updated.length && `${r.updated.length} updated`, r.created && `${r.created} new`, r.errors.length && `${r.errors.length} row${r.errors.length === 1 ? '' : 's'} skipped`].filter(Boolean);
    toast(`${f.name}: ${counts.join(', ') || 'Jira already has these values'}`, r.errors.length > 0);
  };

  const editRow = (r: Schemas['BoardRow'], f: Field, v: string) => canWrite && setEdits((e) => withEdit(e, r, f, v));
  const addSub = (task: ParentTask) => setDialog({ kind: 'sub', task });
  // from the search as it is now, so two quick clicks both count
  const onSort = (k: SortKey, e: MouseEvent) => {
    const add = e.shiftKey;
    navigate({ search: (p) => ({ ...p, sort: sortString(clickSort(parseSort(p.sort), k, add)) }), replace: true });
  };
  const shared = { canWrite, people, name, member, pick, open, addSub };

  const parentCell = (it: Item, all: Record<string, Schemas['BoardRow']>): { text: string; title?: string } => {
    if (it.task) return { text: it.phase || 'New task', title: `${b.epic} · ${b.title}` };
    if (it.parent.startsWith('<new:')) return { text: 'New task', title: parentLabel(it.parent) };
    return { text: it.phase ? `${it.phase} · ${it.parent}` : it.parent, title: `${it.parent} ${all[it.parent]?.summary ?? byId[it.parent]?.row?.summary ?? ''}` };
  };

  const counts = [news.length && `${news.length} new`, edited && `${edited} updated`].filter(Boolean).join(' · ');
  return (
    <div style={{ position: 'relative', display: 'flex', flexDirection: 'column', gap: 12, width: '100%', height: 'calc(100% + 40px)', minHeight: 520, marginBottom: -40 }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, flexWrap: 'wrap' }}>
        <div style={{ flex: 1, minWidth: 240 }}>
          <div className="eyebrow">{b.epic} · {b.title} · {b.tasks.length} tasks · {all.filter((it) => it.row && !it.task).length} sub-tasks</div>
          <h1 className="h1" style={{ marginTop: 2 }}>Tickets</h1>
        </div>
        {canWrite && (
          <>
            <input ref={file} type="file" accept=".csv,text/csv" aria-label="CSV file to import" hidden
              onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) void importFile(f); }} />
            <Button appearance="secondary" iconBefore="file-up" onClick={() => file.current?.click()}
              title={`A CSV of the ticket template (${TEMPLATE.join(', ')}): rows with a Key update that ticket, rows without one are new`}>Import CSV</Button>
            <div style={{ position: 'relative' }}>
              <Button iconBefore="plus" iconAfter="chevron-down" onClick={() => setCreating(!creating)} aria-expanded={creating}>Create</Button>
              {creating && (
                <>
                  <div style={{ position: 'fixed', inset: 0, zIndex: 19 }} onClick={() => setCreating(false)} />
                  <div role="menu" aria-label="Create" style={{ position: 'absolute', right: 0, top: 'calc(100% + 4px)', zIndex: 20, minWidth: 220, padding: 4, borderRadius: 8,
                    border: '1px solid var(--border-subtle)', background: 'var(--surface-card)', boxShadow: '0 8px 24px rgba(9,30,66,0.16)' }}>
                    {([['task', 'Tasks', 'Under the epic'], ['pick', 'Sub-tasks', 'Under one of its Tasks']] as const).map(([k, label, sub]) => (
                      <button key={k} type="button" role="menuitem" className="row-btn" onClick={() => { setCreating(false); setDialog({ kind: k }); }}
                        style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 2, width: '100%', padding: '8px 10px', borderRadius: 6 }}>
                        <b style={{ fontSize: 13 }}>{label}</b><span className="eyebrow">{sub}</span>
                      </button>
                    ))}
                  </div>
                </>
              )}
            </div>
          </>
        )}
      </div>

      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, flexWrap: 'wrap' }}>
        <TextField aria-label="Search" iconBefore="search" placeholder="Key or summary" value={search.q ?? ''} style={{ width: 220 }}
          onChange={(v) => setSearch({ q: v || undefined })} />
        <Select aria-label="Type" value={search.changed ? 'all' : type} disabled={!!search.changed} options={TYPE_OPTIONS} style={{ width: 170 }}
          onChange={(v) => setSearch({ type: v === 'sub' ? undefined : v as 'task' | 'all' })} />
        <Select aria-label="Parent" value={search.parent ?? ''} style={{ width: 220 }}
          options={[{ value: '', label: 'Every Task' }, ...tasks.filter((it) => it.row).map((it) => ({ value: it.id, label: `${it.phase} · ${it.id}` }))]}
          onChange={(v) => setSearch({ parent: v || undefined })} />
        <Select aria-label="Status" value={search.status ?? ''} style={{ width: 140 }} options={[{ value: '', label: 'Any status' }, ...STATUS_OPTIONS]}
          onChange={(v) => setSearch({ status: v || undefined })} />
        <Select aria-label="Assignee" value={search.who ?? ''} style={{ width: 170 }} options={[{ value: '', label: 'Anyone' }, ...people]}
          onChange={(v) => setSearch({ who: v || undefined })} />
        <button type="button" aria-pressed={!!search.changed} onClick={() => setSearch({ changed: search.changed ? undefined : true })}
          style={{ height: 36, padding: '0 12px', borderRadius: 6, border: '1px solid var(--border-subtle)', font: '600 12px/1 var(--font-text)', cursor: 'pointer',
            background: search.changed ? 'var(--hl-amber)' : 'var(--surface-card)', color: search.changed ? 'var(--hl-amber-fg)' : 'var(--text-heading)' }}>
          Changed only · {edited + news.length}
        </button>
        {filtered && <Button appearance="subtle" size="sm" iconBefore="x" onClick={() => setSearch({ q: undefined, type: undefined, parent: undefined, status: undefined, who: undefined, changed: undefined })}>Clear filters</Button>}
        <span className="spacer" />
        <span className="eyebrow" style={{ marginBottom: 10 }}>{shown.length} of {all.length} items</span>
      </div>

      {(sort.length > 0 || !canWrite) && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap', fontSize: 12, color: 'var(--text-muted)', marginTop: -4 }}>
          {sort.length > 0 && <span>Sorted by</span>}
          {sort.map((s, i) => (
            <span key={s.k} style={{ display: 'inline-flex', alignItems: 'center', gap: 4, height: 22, padding: '0 4px 0 8px', borderRadius: 11, background: 'var(--surface-tint)', color: 'var(--text-heading)', fontWeight: 600 }}>
              {sort.length > 1 && `${i + 1} · `}{SORT_LABEL[s.k]} {s.desc ? '↓' : '↑'}
              <IconButton icon="x" size="sm" label={`Stop sorting by ${SORT_LABEL[s.k]}`} onClick={() => setSearch({ sort: sortString(sort.filter((x) => x.k !== s.k)) })} />
            </span>
          ))}
          {sort.length > 0 && <span>· Shift+click a column to sort by it as well</span>}
          {!canWrite && <span>{off}. You can still select tickets to open them in Jira or export them.</span>}
        </div>
      )}

      <section className="card card-clip" aria-label="Tickets" style={{ flex: 1, minHeight: 240, overflow: 'auto' }}>
        <div role="table" style={{ minWidth: 1100 }}>
          <div role="row" style={{ position: 'sticky', top: 0, zIndex: 3, display: 'grid', gridTemplateColumns: GRID, gap: 6, alignItems: 'center', padding: '0 12px', height: 36,
            background: 'var(--surface-hover)', borderBottom: '1px solid var(--border-subtle)', fontSize: 12, fontWeight: 600, color: 'var(--text-muted)' }}>
            <input type="checkbox" aria-label="Select every ticket shown" checked={allShown} onChange={pickShown} />
            <SortHeader k="key" sort={sort} onSort={onSort} />
            <span role="columnheader">Summary</span>
            <span role="columnheader">Parent</span>
            <span role="columnheader">Status</span>
            <SortHeader k="assignee" sort={sort} onSort={onSort} />
            <SortHeader k="reporter" sort={sort} onSort={onSort} />
            <SortHeader k="start" sort={sort} onSort={onSort} />
            <SortHeader k="end" sort={sort} onSort={onSort} />
            <span role="columnheader" style={{ textAlign: 'right' }}>Est. (h)</span>
            <span />
          </div>
          {shown.map((it) => (it.row
            ? <ExistingRow key={it.id} it={it} r={it.row} e={edits[it.id]} picked={sel.has(it.id)} parent={parentCell(it, rows)} edit={editRow} {...shared} />
            : it.draft && <DraftRow key={it.id} it={it} r={it.draft} picked={sel.has(it.id)} parent={parentCell(it, rows)} set={setNew} remove={removeNew} {...shared} />))}
          {!shown.length && (
            <div className="empty-row">
              {all.length ? 'No ticket matches these filters.' : 'This anken has no Tasks in Jira yet.'}
            </div>
          )}
        </div>
      </section>

      {selected.length > 0 && (
        <div role="toolbar" aria-label="Selected tickets" style={{ position: 'absolute', left: '50%', transform: 'translateX(-50%)', bottom: canWrite ? 80 : 20, zIndex: 8,
          display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', width: 'max-content', maxWidth: 'calc(100% - 32px)', padding: '8px 10px 8px 14px', borderRadius: 10,
          border: '1px solid var(--border-strong)', background: 'var(--surface-card)', boxShadow: '0 10px 28px rgba(9,30,66,0.22)' }}>
          <b>{selected.length} selected</b>
          {canWrite && ([['status', 'Set status…', STATUS_OPTIONS], ['assignee_id', 'Set assignee…', people], ['reporter_id', 'Set reporter…', people]] as const).map(([f, label, opts]) => (
            <select key={f} aria-label={label} value="" onChange={(e) => bulk(f)(e.target.value)} style={{ height: 30, padding: '0 8px', borderRadius: 4, border: '1px solid var(--border-subtle)', background: 'var(--surface-card)', color: 'var(--text-heading)' }}>
              <option value="">{label}</option>
              {opts.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          ))}
          <Button appearance="secondary" size="sm" iconBefore="download" onClick={exportSelected}>Export CSV</Button>
          {b.site && selected.some((it) => it.row) && (
            <a href={jqlUrl(b.site, selected.flatMap((it) => (it.row ? [it.id] : [])))} target="_blank" rel="noopener noreferrer" title="Opens these tickets in Jira, ready for Jira's bulk change"
              style={{ display: 'inline-flex', alignItems: 'center', gap: 6, height: 30, padding: '0 12px', borderRadius: 999, border: '1px solid var(--border-strong)', background: 'var(--surface-card)', font: '600 12px/1 var(--font-text)', color: 'var(--text-heading)' }}>
              <Icon name="external-link" size={13} />Open in Jira
            </a>
          )}
          <IconButton icon="x" size="sm" label="Clear selection" onClick={() => setSel(new Set())} />
        </div>
      )}

      {canWrite && (
        <div style={{ flex: 'none', display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', minHeight: 60, boxSizing: 'border-box', padding: '10px 132px 10px 16px', margin: '0 -24px',
          borderTop: '1px solid var(--border-subtle)', background: 'var(--surface-card)', boxShadow: '0 -4px 16px rgba(9,30,66,0.08)' }}>
          <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-heading)' }}>{counts ? `Not committed · ${counts}` : 'No pending changes'}</span>
          {bad.length > 0 && <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)' }}>{bad.length} new row{bad.length === 1 ? ' needs' : 's need'} a summary or assignee</span>}
          {skipped.length > 0 && (
            <button type="button" className="row-btn" onClick={() => setDialog({ kind: 'errors' })} style={{ fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)', textDecoration: 'underline' }}>
              Import skipped {skipped.length} row{skipped.length === 1 ? '' : 's'}
            </button>
          )}
          <span className="spacer" />
          {(counts || skipped.length > 0) && <Button appearance="subtle" onClick={discard}>Discard all</Button>}
          <Button iconBefore="eye" disabled={!counts || bad.length > 0 || preview.isPending} onClick={openReview}>
            {preview.isPending ? 'Reading Jira…' : 'Review & commit'}
          </Button>
        </div>
      )}

      {dialog?.kind === 'task' && (
        <NewTaskDialog templates={b.templates.map((t) => ({ ...t, exists: t.exists || news.some((n) => !n.subtask && n.summary === t.match) }))} people={people}
          add={(r) => setNews((n) => [...n, ...r])} close={() => setDialog(null)} />
      )}
      {dialog?.kind === 'pick' && (
        <PickTaskDialog tasks={tasks} label={taskLabel} initial={search.parent} close={() => setDialog(null)}
          next={(it) => setDialog({ kind: 'sub', task: it.row
            ? { key: it.id, summary: it.row.summary, workflow: !!it.workflow }
            : { ref: it.id, summary: valueOf(it, edits, 'summary') || 'New task', workflow: false } })} />
      )}
      {dialog?.kind === 'sub' && (
        <AddSubtasksDialog epic={b.epic} task={dialog.task} people={people} members={members}
          add={(r) => setNews((n) => [...n, ...r])} close={() => setDialog(null)} />
      )}
      {dialog?.kind === 'errors' && <ImportErrorsDialog errors={skipped} close={() => setDialog(null)} />}
      {review && (
        <CommitDialog preview={review.preview} parentLabel={parentLabel} name={name} commit={commit} close={() => setReview(null)} />
      )}
    </div>
  );
}

/** A sortable column's header: its place in a multi-column sort and the direction; Shift+click adds it to the sort. */
function SortHeader({ k, sort, onSort }: { k: SortKey; sort: SortBy[]; onSort: (k: SortKey, e: MouseEvent) => void }) {
  const at = sort.findIndex((s) => s.k === k);
  const s = sort[at];
  return (
    <span role="columnheader" aria-sort={s ? (s.desc ? 'descending' : 'ascending') : 'none'}>
      <button type="button" className="row-btn" onClick={(e) => onSort(k, e)} title="Click to sort by this column · Shift+click to add it to the sort"
        aria-label={`Sort by ${SORT_LABEL[k]}`}
        style={{ display: 'inline-flex', alignItems: 'center', gap: 4, font: 'inherit', color: s ? 'var(--text-link)' : 'inherit', cursor: 'pointer' }}>
        {SORT_LABEL[k]}
        <Icon name={s ? (s.desc ? 'arrow-down' : 'arrow-up') : 'arrow-up-down'} size={12} style={{ opacity: s ? 1 : 0.45 }} />
        {s && sort.length > 1 && (
          <span style={{ width: 14, height: 14, borderRadius: '50%', background: 'var(--text-link)', color: 'var(--surface-card)', font: '700 9px/14px var(--font-text)', textAlign: 'center' }}>{at + 1}</span>
        )}
      </button>
    </span>
  );
}

interface RowProps {
  it: Item; picked: boolean; parent: { text: string; title?: string }; canWrite: boolean; people: Option[];
  name: (id: string) => string; member: (id: string) => Schemas['Member'] | undefined;
  pick: (id: string) => void; open: (key: string) => void; addSub: (task: ParentTask) => void;
}

const rowStyle = (picked: boolean, mark?: string): CSSProperties => ({
  display: 'grid', gridTemplateColumns: GRID, gap: 6, alignItems: 'center', padding: '2px 12px', borderBottom: '1px solid var(--border-subtle)',
  background: picked ? 'color-mix(in oklch, var(--surface-tint) 60%, transparent)' : undefined, boxShadow: mark ? `inset 3px 0 0 ${mark}` : undefined,
});

function TypeIcon({ task }: { task: boolean }) {
  return <span title={task ? 'Task' : 'Sub-task'} style={{ flex: 'none', width: 14, height: 14, borderRadius: 3, background: task ? 'oklch(0.66 0.13 160)' : 'oklch(0.66 0.12 235)' }} />;
}

function Person({ id, member }: { id: string; member: RowProps['member'] }) {
  return id ? <Avatar a={av(member(id), id)} size={20} style={{ marginRight: -2 }} /> : <span style={{ width: 18, flex: 'none' }} />;
}

/** A Task or sub-task already in Jira, edited in place; `e` holds its edits not yet committed. */
function ExistingRow({ it, r, e = {}, picked, parent, canWrite, people, name, member, pick, open, edit, addSub }: RowProps & {
  r: Schemas['BoardRow']; e?: Edits[string]; edit: (r: Schemas['BoardRow'], f: Field, v: string) => void;
}) {
  const value = (f: Field) => e[f] ?? saved(r, f);
  const was = (f: Field) => (e[f] != null ? `Was ${saved(r, f) || '–'}` : undefined);
  const changed = Object.keys(e).length > 0;
  const selectStyle = (f: Field) => cell(e[f] != null, { cursor: canWrite ? 'pointer' : 'default', paddingLeft: 4 });
  return (
    <div role="row" style={rowStyle(picked, changed ? 'var(--hl-amber-fg)' : undefined)}>
      <input type="checkbox" aria-label={`Select ${r.key}`} checked={picked} onChange={() => pick(r.key)} />
      <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <TypeIcon task={it.task} />
        <button type="button" className="row-btn key" onClick={() => open(r.key)} title="Open the ticket">{r.key}</button>
      </span>
      <span style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
        <input aria-label={`${r.key} summary`} value={value('summary')} disabled={!canWrite} title={was('summary') ?? value('summary')}
          onChange={(ev) => edit(r, 'summary', ev.target.value)} style={cell(e.summary != null, { flex: 1, minWidth: 0, fontWeight: it.task ? 600 : 400, textOverflow: 'ellipsis' })} />
        {changed && <span title="Edited · not committed" style={tag('var(--hl-amber)', 'var(--hl-amber-fg)')}>CHANGED</span>}
      </span>
      <span className="ellipsis" title={parent.title} style={{ fontSize: 12, color: 'var(--text-muted)' }}>{parent.text}</span>
      <span>
        <select aria-label={`${r.key} status`} value={value('status')} disabled={!canWrite} title={was('status')} onChange={(ev) => edit(r, 'status', ev.target.value)}
          style={lozenge(value('status'), e.status != null, canWrite)}>
          {STATUS_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      </span>
      {(['assignee_id', 'reporter_id'] as const).map((f) => (
        <span key={f} style={{ display: 'flex', alignItems: 'center', gap: 4, minWidth: 0 }}>
          <Person id={value(f)} member={member} />
          <select aria-label={`${r.key} ${FIELD_LABEL[f].toLowerCase()}`} value={value(f)} disabled={!canWrite} title={was(f) && `Was ${name(r[f] ?? '') || '–'}`}
            onChange={(ev) => edit(r, f, ev.target.value)} style={selectStyle(f)}>
            {!value(f) && <option value="">{f === 'assignee_id' ? 'Unassigned' : '–'}</option>}
            {value(f) && !people.some((p) => p.value === value(f)) && <option value={value(f)}>{name(value(f))}</option>}
            {people.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
          </select>
        </span>
      ))}
      {(['planned_start', 'planned_end'] as const).map((f) => (
        <DateField key={f} bare aria-label={`${r.key} ${FIELD_LABEL[f].toLowerCase()}`} value={value(f)} disabled={!canWrite} title={was(f)}
          onChange={(v) => edit(r, f, v)} style={cell(e[f] != null)} />
      ))}
      <input type="number" min={0} step={0.01} aria-label={`${r.key} estimate`} value={value('estimate_h')} disabled={!canWrite} title={was('estimate_h')}
        onChange={(ev) => edit(r, 'estimate_h', ev.target.value)} style={cell(e.estimate_h != null, { textAlign: 'right' })} />
      <span style={{ display: 'flex', justifyContent: 'flex-end' }}>
        {it.task && canWrite && (
          <IconButton icon="plus" label={`Add sub-tasks to ${r.key}`} size="sm" onClick={() => addSub({ key: r.key, summary: r.summary, workflow: !!it.workflow })} />
        )}
      </span>
    </div>
  );
}

/** A Task or sub-task to create: every field is its own until commit. */
function DraftRow({ it, r, picked, parent, canWrite, people, member, pick, addSub, set, remove }: RowProps & {
  r: NewRow; set: (ref: string, k: keyof NewRow, v: string) => void; remove: (ref: string) => void;
}) {
  const need = missing(r);
  const input = (k: keyof NewRow) => (v: string) => set(r.ref, k, v);
  return (
    <div role="row" style={{ ...rowStyle(picked, 'var(--hl-ok-fg)'), background: picked ? rowStyle(true).background : 'color-mix(in oklch, var(--hl-ok) 45%, transparent)' }}>
      <input type="checkbox" aria-label={`Select new ${r.summary || 'ticket'}`} checked={picked} onChange={() => pick(r.ref)} />
      <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <TypeIcon task={it.task} />
        <span title="Draft · gets a Jira key on commit" style={tag('var(--hl-ok)', 'var(--hl-ok-fg)')}>NEW</span>
      </span>
      <span style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
        <input aria-label="Summary" value={r.summary} placeholder="Summary" onChange={(e) => input('summary')(e.target.value)}
          style={cell(true, { flex: 1, minWidth: 0, background: 'transparent', fontWeight: it.task ? 600 : 400, borderColor: need.includes('summary') ? 'var(--hl-red-fg)' : 'transparent' })} />
        {need.length > 0 && <span title={`Fill in: ${need.join(', ')}`}><Icon name="circle-alert" size={14} style={{ color: 'var(--hl-red-fg)' }} /></span>}
      </span>
      <span className="ellipsis" title={parent.title} style={{ fontSize: 12, color: 'var(--text-muted)' }}>{parent.text}</span>
      <span><span style={lozenge('N', false, false)}>{statusOf('N').label}</span></span>
      {(['assignee_id', 'reporter_id'] as const).map((k) => (
        <span key={k} style={{ display: 'flex', alignItems: 'center', gap: 4, minWidth: 0 }}>
          <Person id={r[k]} member={member} />
          <select aria-label={`New ${FIELD_LABEL[k].toLowerCase()}`} value={r[k]} onChange={(e) => input(k)(e.target.value)}
            style={cell(false, { cursor: 'pointer', paddingLeft: 4, borderColor: k === 'assignee_id' && !r[k] ? 'var(--hl-red-fg)' : 'transparent' })}>
            <option value="">{k === 'reporter_id' ? 'Default' : '–'}</option>
            {people.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
          </select>
        </span>
      ))}
      {(['planned_start', 'planned_end'] as const).map((k) => (
        <DateField key={k} bare aria-label={`New ${FIELD_LABEL[k].toLowerCase()}`} value={r[k]} onChange={input(k)} style={cell(false)} />
      ))}
      <input type="number" min={0} step={0.01} aria-label="New estimate" value={r.estimate_h} onChange={(e) => input('estimate_h')(e.target.value)}
        style={cell(false, { textAlign: 'right' })} />
      <span style={{ display: 'flex', justifyContent: 'flex-end' }}>
        {it.task && canWrite && (
          <IconButton icon="plus" label="Add sub-tasks to the new task" size="sm" onClick={() => addSub({ ref: r.ref, summary: r.summary || 'New task', workflow: false })} />
        )}
        <IconButton icon="trash-2" label="Remove draft" size="sm" onClick={() => remove(r.ref)} />
      </span>
    </div>
  );
}

/** Create › Sub-tasks: the Task they go under, then the Add sub-tasks dialog. */
function PickTaskDialog({ tasks, label, initial, next, close }: {
  tasks: Item[]; label: (it: Item) => string; initial?: string; next: (it: Item) => void; close: () => void;
}) {
  const [id, setId] = useState(tasks.find((t) => t.id === initial)?.id ?? '');
  const it = tasks.find((t) => t.id === id);
  return (
    <div className="scrim" onClick={close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Sub-tasks under a Task" onClick={(e) => e.stopPropagation()} style={{ width: 'min(560px,100%)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="dialog-title" style={{ flex: 1 }}>New sub-tasks</span>
          <IconButton icon="x" label="Close" size="sm" onClick={close} />
        </div>
        <Select label="Under the Task" value={id} onChange={setId}
          options={[{ value: '', label: 'Choose a Task…' }, ...tasks.map((t) => ({ value: t.id, label: label(t), group: t.phase || 'New' }))]} />
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button appearance="subtle" onClick={close}>Cancel</Button>
          <Button disabled={!it} onClick={() => it && next(it)}>Next</Button>
        </div>
      </div>
    </div>
  );
}

/** The rows an import skipped, with the line of the file and why. */
function ImportErrorsDialog({ errors, close }: { errors: ImportError[]; close: () => void }) {
  return (
    <div className="scrim" onClick={close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Rows not imported" onClick={(e) => e.stopPropagation()}
        style={{ width: 'min(720px,100%)', maxHeight: '100%', overflow: 'auto' }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
          <div style={{ flex: 1 }}>
            <div className="dialog-title">Rows not imported · {errors.length}</div>
            <div className="eyebrow">Fix them in the file and import it again; the other rows are in the list already.</div>
          </div>
          <IconButton icon="x" label="Close" size="sm" onClick={close} />
        </div>
        <div style={{ border: '1px solid var(--border-subtle)', borderRadius: 8, overflow: 'hidden', fontSize: 13 }}>
          {errors.map((e) => (
            <div key={e.line} style={{ display: 'grid', gridTemplateColumns: '64px 96px minmax(0,1fr)', gap: 10, padding: '8px 12px', borderTop: '1px solid var(--border-subtle)' }}>
              <span className="eyebrow">Line {e.line}</span>
              <span className="key">{e.key || 'New'}</span>
              <span>{e.message}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

