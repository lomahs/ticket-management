import { useQueries } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { Button, IconButton, Select, TextField, type Option } from '../../../shared/ds';
import { dm, f1 } from '../../../shared/format/dates';
import { functionPreviewQuery } from '../../matrix';
import { newRef, type NewRow } from '../model/draft';

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

/** The Task new sub-tasks go under: an existing one (key) or one added in this batch (ref). */
export interface ParentTask { key?: string; ref?: string; summary: string; workflow: boolean }

/**
 * Sub-tasks for one Task, as drafts. Under a workflow Task each function name becomes its five step sub-tasks, named and
 * planned like Add function does; otherwise (or for a Task that is new itself) one sub-task at a time.
 */
export function AddSubtasksDialog({ epic, task, people, members, add, close }: {
  epic: string; task: ParentTask; people: Option[]; members: Schemas['Member'][]; add: (rows: NewRow[]) => void; close: () => void;
}) {
  const canFunctions = task.workflow && !!task.key;
  const [mode, setMode] = useState<'functions' | 'single'>(canFunctions ? 'functions' : 'single');
  const parent = task.key ? { parent_key: task.key } : { parent_ref: task.ref };
  return (
    <div className="scrim" onClick={close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Add sub-tasks" onClick={(e) => e.stopPropagation()}
        style={{ width: 'min(720px,100%)', maxHeight: '100%', overflow: 'auto' }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="dialog-title">Add sub-tasks</div>
            <div className="eyebrow ellipsis">{task.key ?? 'New task'} · {task.summary}</div>
          </div>
          <IconButton icon="x" label="Close" size="sm" onClick={close} />
        </div>
        {canFunctions && (
          <div className="seg" role="group" aria-label="Kind" style={{ alignSelf: 'flex-start' }}>
            {([['functions', 'Functions · 5 steps each'], ['single', 'Single sub-task']] as const).map(([k, label]) => (
              <button key={k} type="button" aria-pressed={mode === k} onClick={() => setMode(k)}
                style={{ background: mode === k ? 'var(--button-primary-bg)' : 'transparent', color: mode === k ? 'var(--button-primary-fg)' : 'var(--text-heading)' }}>
                {label}
              </button>
            ))}
          </div>
        )}
        {mode === 'functions' && task.key
          ? <Functions epic={epic} parentKey={task.key} people={people} members={members} add={(rows) => { add(rows); close(); }} close={close} />
          : <Single people={people} add={(row) => { add([{ ...row, ...parent }]); close(); }} close={close} />}
      </div>
    </div>
  );
}

function Single({ people, add, close }: { people: Option[]; add: (row: NewRow) => void; close: () => void }) {
  const [r, setR] = useState({ summary: '', assignee_id: '', reporter_id: '', planned_start: '', planned_end: '', estimate_h: '' });
  const set = (k: keyof typeof r) => (v: string) => setR((x) => ({ ...x, [k]: v }));
  return (
    <>
      <TextField label="Summary" value={r.summary} onChange={set('summary')} placeholder="[BookUI][LocalTask][ITA]_Login_Execute" />
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
        <Select label="Assignee" value={r.assignee_id} options={[{ value: '', label: '–' }, ...people]} onChange={set('assignee_id')} />
        <Select label="Reporter" value={r.reporter_id} options={[{ value: '', label: 'The Jira account (default)' }, ...people]} onChange={set('reporter_id')} />
        <TextField label="Planned Start" type="date" value={r.planned_start} onChange={set('planned_start')} />
        <TextField label="Planned End" type="date" value={r.planned_end} onChange={set('planned_end')} />
        <TextField label="Estimate (h)" type="number" min={0} step={0.5} value={r.estimate_h} onChange={set('estimate_h')} />
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span className="eyebrow" style={{ flex: 1 }}>Added as a draft. Nothing reaches Jira until you commit.</span>
        <Button appearance="subtle" onClick={close}>Cancel</Button>
        <Button disabled={!r.summary.trim() || !r.assignee_id} onClick={() => add({ ref: newRef(), subtask: true, ...r })}>Add sub-task</Button>
      </div>
    </>
  );
}

function Functions({ epic, parentKey, people, members, add, close }: {
  epic: string; parentKey: string; people: Option[]; members: Schemas['Member'][]; add: (rows: NewRow[]) => void; close: () => void;
}) {
  const byRole = (role: string) => members.filter((m) => m.role === role).map((m) => ({ value: m.id, label: m.name }));
  const first = (role: string) => byRole(role)[0]?.value ?? '';
  const [f, setF] = useState({ names: '', ba: first('BA'), bal: first('BAL'), jm: first('JM'), create_hours: '8', reporter_id: '' });
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  const names = useDebounced(f.names, 400).split('\n').map((s) => s.trim()).filter(Boolean);
  const ready = !!(f.ba && f.bal && f.jm && Number(f.create_hours) > 0);
  const previews = useQueries({
    queries: names.map((name) => functionPreviewQuery(epic, { parent_key: parentKey, name, ba: f.ba, bal: f.bal, jm: f.jm,
      create_hours: Number(f.create_hours) }, ready)),
  });
  const name = (id: string) => people.find((p) => p.value === id)?.label ?? id;
  const error = previews.find((p) => p.isError)?.error;
  const done = names.length > 0 && previews.every((p) => p.data);
  const submit = () => add(previews.flatMap((p) => p.data!.steps.map((s) => ({
    ref: newRef(), subtask: true, parent_key: parentKey, summary: s.summary, assignee_id: s.assignee_id, reporter_id: f.reporter_id,
    planned_start: s.start, planned_end: s.end, estimate_h: String(s.estimate_h),
  }))));
  return (
    <>
      <label style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        <span className="title" style={{ fontSize: 13 }}>Function names</span>
        <textarea value={f.names} onChange={(e) => set('names')(e.target.value)} rows={4} placeholder="One function per line"
          style={{ font: 'var(--type-body-sm)', padding: 10, borderRadius: 'var(--radius-md)', border: '1px solid var(--border-strong)', resize: 'vertical' }} />
        <span className="eyebrow">Each function gets the 5 workflow steps, named by the anken's name rule and planned from tomorrow.</span>
      </label>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0,1fr))', gap: 12 }}>
        <Select label="BA · Create, Fix" value={f.ba} options={byRole('BA')} onChange={set('ba')} />
        <Select label="Reviewer BAL" value={f.bal} options={byRole('BAL')} onChange={set('bal')} />
        <Select label="Reviewer JM" value={f.jm} options={byRole('JM')} onChange={set('jm')} />
        <TextField label="Create effort (h)" type="number" min={0} step={0.5} value={f.create_hours} onChange={set('create_hours')} />
        <Select label="Reporter" value={f.reporter_id} options={[{ value: '', label: 'The Jira account (default)' }, ...people]} onChange={set('reporter_id')} />
      </div>
      {error && <span role="alert" style={{ fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)' }}>{errorMessage(error)}</span>}
      {previews.map((p, i) => p.data && (
        <div key={i} style={{ border: '1px solid var(--border-subtle)', borderRadius: 8, overflow: 'hidden', fontSize: 12 }}>
          {p.data.steps.map((s) => (
            <div key={s.step} style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) 90px 56px 110px', gap: 8, padding: '6px 10px', borderTop: '1px solid var(--border-subtle)' }}>
              <span className="ellipsis heading" title={s.summary}>{s.summary}</span>
              <span>{name(s.assignee_id)}</span>
              <span className="mono">{f1(s.estimate_h)}h</span>
              <span className="mono muted">{dm(s.start)} – {dm(s.end)}</span>
            </div>
          ))}
        </div>
      ))}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span className="eyebrow" style={{ flex: 1 }}>Added as drafts. Nothing reaches Jira until you commit.</span>
        <Button appearance="subtle" onClick={close}>Cancel</Button>
        <Button disabled={!done} onClick={submit}>Add {names.length * 5 || ''} sub-tasks</Button>
      </div>
    </>
  );
}
