import { useQuery } from '@tanstack/react-query';
import { useState, type ChangeEvent, type CSSProperties, type MutableRefObject } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useCan } from '../../../shared/api/queries';
import { Button, IconButton } from '../../../shared/ds';
import { hrs } from '../../../shared/format/dates';
import { useToast } from '../../../shared/state/toast';
import { Loading, QueryError } from '../../../shared/ui/states';
import { ESTIMATION_TEMPLATE_URL, estimationQuery, useImportEstimation, useSaveEstimation, type EstimationRow, type ImportRow, type TaskOption } from './api';

/** Saves what the editor holds; set while it is shown. */
export type SaveRef = MutableRefObject<(() => Promise<unknown>) | null>;

const CELL: CSSProperties = { height: 30, boxSizing: 'border-box', padding: '0 8px', border: '1px solid var(--border-strong)', borderRadius: 6, background: 'var(--surface-card)', color: 'var(--text-heading)', font: '500 13px/1 var(--font-text)', width: '100%' };
const COLS = 'minmax(200px,2.5fr) minmax(140px,2fr) minmax(160px,3fr) 70px 80px 36px';

/**
 * The estimation of an anken: one row per function or ticket, each under a Task of the epic that matches the Task pattern
 * (the row's phase and subphase are the Task's); {@code save} saves the rows.
 */
export function EstimationEditor({ epic, save }: { epic: string; save: SaveRef }) {
  const q = useQuery(estimationQuery(epic));
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  return <Editor key={JSON.stringify(q.data.rows)} epic={epic} rows={q.data.rows} phases={q.data.phases} tasks={q.data.tasks} saveRef={save} />;
}

const blank = (epic: string): EstimationRow => ({ id: 0, epic_key: epic, position: 0, phase: '', subphase: '', function_name: '', steps: 5, effort_h: 0, ba_id: null, bal_id: null, jm_id: null, task_key: null, pushed_at: null });

function Editor({ epic, rows: saved, phases, tasks, saveRef }: {
  epic: string; rows: EstimationRow[]; phases: { phase: string; total_effort_h: number }[]; tasks: TaskOption[]; saveRef: SaveRef;
}) {
  const admin = useCan('planning', 'write');
  const toast = useToast();
  const save = useSaveEstimation(epic);
  const imp = useImportEstimation(epic);
  const [rows, setRows] = useState<EstimationRow[]>(saved);
  const [preview, setPreview] = useState<ImportRow[] | null>(null);
  const edit = (i: number, p: Partial<EstimationRow>) => setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...p } : r)));
  saveRef.current = () => save.mutateAsync(rows.map((r, i) => ({ ...r, position: i })));
  const unlinked = rows.filter((r) => !r.pushed_at && !r.task_key).length;
  const taskName = (r: EstimationRow) => tasks.find((t) => t.key === r.task_key)?.name ?? (r.pushed_at ? r.subphase : '');
  const pickTask = (i: number, key: string) => edit(i, { task_key: key || null });
  const good = (preview ?? []).filter((r) => r.errors.length === 0);
  const onFile = (e: ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (f) imp.mutate(f, { onSuccess: setPreview, onError: (err) => toast(errorMessage(err), true) });
    e.target.value = '';
  };
  const addImported = () => {
    setRows((rs) => [...rs, ...good.map((r) => ({ ...blank(epic), task_key: r.task, function_name: r.function, steps: r.steps ?? null, effort_h: r.effort_h ?? 0 }))]);
    setPreview(null);
  };
  return (
    <>
      <section className="card" aria-label="Effort per phase" style={{ padding: '14px 18px', display: 'flex', gap: 18, flexWrap: 'wrap', alignItems: 'baseline' }}>
        <span className="title">Effort per phase</span>
        {phases.length === 0 && <span className="muted">No rows yet.</span>}
        {phases.map((p) => (
          <span key={p.phase} style={{ display: 'inline-flex', gap: 6, alignItems: 'baseline' }}>
            <b className="heading">{p.phase}</b><span>{hrs(p.total_effort_h)}</span>
          </span>
        ))}
        <span className="eyebrow" style={{ flexBasis: '100%' }}>Function effort = Create × step weights, without JM Review. A row without steps counts its effort as entered.</span>
      </section>

      <section className="card card-clip" aria-label="Estimation rows">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)' }}>
          <span className="title">Rows</span>
          <span className="eyebrow">{`${rows.length - unlinked} of ${rows.length} rows linked to a Task`}</span>
          <span className="spacer" />
          {admin && (
            <>
              <a href={ESTIMATION_TEMPLATE_URL} style={{ fontSize: 12 }}>Template</a>
              <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, cursor: 'pointer' }}>
                Import Excel / CSV
                <input type="file" accept=".xlsx,.csv" aria-label="Import file" onChange={onFile} style={{ fontSize: 12 }} />
              </label>
              <Button appearance="secondary" size="sm" onClick={() => setRows((rs) => [...rs, blank(epic)])}>Add row</Button>
            </>
          )}
        </div>
        {preview && (
          <div role="status" style={{ padding: '10px 16px', borderBottom: '1px solid var(--border-subtle)', display: 'flex', flexDirection: 'column', gap: 4 }}>
            <span>{good.length} of {preview.length} rows can be added.</span>
            {preview.filter((r) => r.errors.length).map((r) => (
              <span key={r.line} style={{ fontSize: 12, color: 'var(--hl-red-fg)' }}>{`Line ${r.line}: ${r.errors.join(', ')}`}</span>
            ))}
            <div style={{ display: 'flex', gap: 8 }}>
              <Button size="sm" disabled={!good.length} onClick={addImported}>{`Add ${good.length} row${good.length === 1 ? '' : 's'}`}</Button>
              <Button size="sm" appearance="subtle" onClick={() => setPreview(null)}>Cancel</Button>
            </div>
          </div>
        )}
        <div style={{ overflowX: 'auto' }}>
          <div style={{ minWidth: 960, display: 'flex', flexDirection: 'column' }}>
            <div style={{ display: 'grid', gridTemplateColumns: COLS, gap: 8, padding: '8px 16px', fontSize: 11, color: 'var(--text-muted)' }}>
              <span>Task</span><span>Task name</span><span>Function name</span><span>Steps</span><span>Effort (h)</span><span />
            </div>
            {rows.map((r, i) => {
              const ro = !admin || !!r.pushed_at;
              const n = i + 1;
              return (
                <div key={r.id || `new-${i}`} style={{ display: 'grid', gridTemplateColumns: COLS, gap: 8, alignItems: 'center', padding: '4px 16px', borderTop: '1px solid var(--border-subtle)' }}>
                  <select aria-label={`Task, row ${n}`} disabled={ro} value={r.task_key ?? ''} onChange={(e) => pickTask(i, e.target.value)}
                    style={{ ...CELL, borderColor: r.task_key || ro ? 'var(--border-strong)' : 'var(--hl-red-fg)' }}>
                    <option value="">Choose Task…</option>
                    {r.task_key && !tasks.some((t) => t.key === r.task_key) && <option value={r.task_key}>{r.task_key}</option>}
                    {tasks.map((t) => <option key={t.key} value={t.key}>{`${t.key} · ${t.summary}`}</option>)}
                  </select>
                  <span aria-label={`Task name, row ${n}`} className="ellipsis" title={taskName(r)} style={{ fontSize: 13, color: 'var(--text-heading)' }}>{taskName(r) || '–'}</span>
                  <input aria-label={`Function, row ${n}`} disabled={ro} value={r.function_name} onChange={(e) => edit(i, { function_name: e.target.value })} style={CELL} />
                  <select aria-label={`Steps, row ${n}`} disabled={ro} value={r.steps ?? ''} onChange={(e) => edit(i, { steps: e.target.value ? Number(e.target.value) : null })} style={CELL}>
                    <option value="">–</option><option value="3">3</option><option value="5">5</option>
                  </select>
                  <input aria-label={`Effort (h), row ${n}`} disabled={ro} type="number" min={0} step={0.5} value={r.effort_h || ''} onChange={(e) => edit(i, { effort_h: Number(e.target.value) })} style={CELL} />
                  {r.pushed_at ? <span className="eyebrow" title="Already in Jira">In Jira</span>
                    : admin ? <IconButton icon="trash-2" label={`Remove row ${n}`} size="sm" appearance="subtle" onClick={() => setRows((rs) => rs.filter((_, j) => j !== i))} /> : <span />}
                </div>
              );
            })}
          </div>
        </div>
      </section>
    </>
  );
}
