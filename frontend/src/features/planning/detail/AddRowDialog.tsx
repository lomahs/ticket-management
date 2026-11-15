import { useState } from 'react';
import { ApiError, errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { Button, IconButton, Select, TextField } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { useAddRow } from './api';

/** A function (3 or 5 steps, its Create effort) or a ticket (its effort) the plan is missing; it goes into the estimation. */
export function AddRowDialog({ epic, kind, tasks, close }: { epic: string; kind: 'function' | 'task'; tasks: Schemas['TaskOption'][]; close: () => void }) {
  const toast = useToast();
  const add = useAddRow(epic);
  const fn = kind === 'function';
  const [f, setF] = useState({ task: tasks[0]?.key ?? '', name: '', steps: '5', effort: '' });
  const [err, setErr] = useState<ApiError | null>(null);
  const edit = (p: Partial<typeof f>) => { setErr(null); setF((x) => ({ ...x, ...p })); };
  const effort = Number(f.effort);
  const ok = f.task && f.name.trim() && f.effort.trim() && effort > 0;
  const submit = () => add.mutate({ task_key: f.task, function_name: f.name.trim(), steps: fn ? Number(f.steps) : null, effort_h: effort }, {
    onSuccess: () => { toast(`${f.name.trim()} added to the estimation`); close(); },
    onError: (e) => (e instanceof ApiError && e.errors.length ? setErr(e) : toast(errorMessage(e), true)),
  });
  const title = fn ? 'Add function' : 'Add task';
  return (
    <div className="scrim" onClick={close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()} style={{ width: 'min(480px,100%)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="dialog-title" style={{ flex: 1 }}>{title}</span>
          <IconButton icon="x" label="Close" size="sm" onClick={close} />
        </div>
        <Select label="Task" value={f.task} onChange={(v) => edit({ task: v })}
          options={tasks.map((t) => ({ value: t.key, label: `${t.key} · ${t.summary}` }))} />
        <TextField label={fn ? 'Function' : 'Name'} value={f.name} onChange={(v) => edit({ name: v })} aria-invalid={!!err?.field('function_name')} />
        {fn && <Select label="Steps" value={f.steps} onChange={(v) => edit({ steps: v })}
          options={[{ value: '5', label: '5 steps (with JM)' }, { value: '3', label: '3 steps' }]} />}
        <TextField label={fn ? 'Create effort (h)' : 'Effort (h)'} type="number" value={f.effort} onChange={(v) => edit({ effort: v })}
          aria-invalid={!!err?.field('effort_h')} />
        {err && <div role="alert" style={{ fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)' }}>{err.errors.map((x) => x.message).join(' ')}</div>}
        <span className="eyebrow">It is added to the estimation, so it counts in the phase's BA effort; save the master schedule again to move its End.</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="spacer" />
          <Button appearance="subtle" onClick={close}>Cancel</Button>
          <Button disabled={add.isPending || !ok} onClick={submit}>Add</Button>
        </div>
      </div>
    </div>
  );
}
