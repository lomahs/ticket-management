import { useState } from 'react';
import type { Schemas } from '../../../shared/api/types';
import { Button, IconButton, Select, TextField, type Option } from '../../../shared/ds';
import { newRef, type NewRow } from '../model/draft';

/**
 * New Tasks of an anken, as drafts: the Tasks of its fixed set (Settings › Phases & tasks) that it does not have yet, and any
 * other Task. Assignee, reporter and dates are shared by all of them; each can be changed in the table afterwards.
 */
export function NewTaskDialog({ templates, people, add, close }: {
  templates: Schemas['BoardTemplate'][]; people: Option[]; add: (rows: NewRow[]) => void; close: () => void;
}) {
  const [picked, setPicked] = useState<Record<number, string | undefined>>({});
  const [custom, setCustom] = useState<string[]>(templates.every((t) => t.exists) ? [''] : []);
  const [common, setCommon] = useState({ assignee_id: '', reporter_id: '', planned_start: '', planned_end: '' });
  const set = (k: keyof typeof common) => (v: string) => setCommon((c) => ({ ...c, [k]: v }));
  const summaries = [...Object.values(picked).filter((s): s is string => s != null), ...custom].map((s) => s.trim()).filter(Boolean);
  const submit = () => {
    add(summaries.map((summary) => ({ ref: newRef(), subtask: false, summary, ...common, estimate_h: '' })));
    close();
  };
  return (
    <div className="scrim" onClick={close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="New tasks" onClick={(e) => e.stopPropagation()}
        style={{ width: 'min(640px,100%)', maxHeight: '100%', overflow: 'auto' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="dialog-title">New tasks</span>
          <IconButton icon="x" label="Close" size="sm" onClick={close} />
        </div>
        {templates.length > 0 && (
          <fieldset style={{ border: 0, padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 6 }}>
            <legend className="title" style={{ fontSize: 12, marginBottom: 6 }}>From the task set of this anken</legend>
            {templates.map((t, i) => (
              <div key={i} style={{ display: 'grid', gridTemplateColumns: '24px minmax(0,1fr) 120px', gap: 8, alignItems: 'center' }}>
                <input type="checkbox" aria-label={`Add ${t.match}`} disabled={t.exists} checked={picked[i] != null}
                  onChange={(e) => setPicked((p) => ({ ...p, [i]: e.target.checked ? t.match : undefined }))} />
                <TextField aria-label={`Summary of ${t.match}`} value={picked[i] ?? t.match} disabled={t.exists || picked[i] == null}
                  onChange={(v) => setPicked((p) => ({ ...p, [i]: v }))} />
                <span className="eyebrow">{t.exists ? 'Exists' : `${t.phase}${t.workflow ? '' : ' · no workflow'}`}</span>
              </div>
            ))}
            {Object.values(picked).some((s) => s?.includes('*')) && <span className="eyebrow">Replace each * with the real name.</span>}
          </fieldset>
        )}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <div className="title" style={{ fontSize: 12 }}>Other tasks</div>
          {custom.map((s, i) => (
            <div key={i} style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <TextField aria-label={`Task ${i + 1}`} placeholder="[BookUI][LocalTask] Change Request" value={s} style={{ flex: 1 }}
                onChange={(v) => setCustom((c) => c.map((x, j) => (j === i ? v : x)))} />
              <IconButton icon="x" label="Remove" size="sm" onClick={() => setCustom((c) => c.filter((_, j) => j !== i))} />
            </div>
          ))}
          <Button appearance="subtle" size="sm" iconBefore="plus" onClick={() => setCustom((c) => [...c, ''])} style={{ alignSelf: 'flex-start' }}>Another task</Button>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <Select label="Assignee" value={common.assignee_id} options={[{ value: '', label: '–' }, ...people]} onChange={set('assignee_id')} />
          <Select label="Reporter" value={common.reporter_id} options={[{ value: '', label: 'The Jira account (default)' }, ...people]} onChange={set('reporter_id')} />
          <TextField label="Planned Start" type="date" value={common.planned_start} onChange={set('planned_start')} />
          <TextField label="Planned End" type="date" value={common.planned_end} onChange={set('planned_end')} />
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="eyebrow" style={{ flex: 1 }}>Added as drafts. Nothing reaches Jira until you commit.</span>
          <Button appearance="subtle" onClick={close}>Cancel</Button>
          <Button disabled={!summaries.length || !common.assignee_id} onClick={submit}>
            Add {summaries.length || ''} task{summaries.length === 1 ? '' : 's'}
          </Button>
        </div>
      </div>
    </div>
  );
}
