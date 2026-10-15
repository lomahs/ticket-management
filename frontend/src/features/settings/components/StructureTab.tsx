import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { Button, IconButton, Select, TextField } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { Loading, QueryError } from '../../../shared/ui/states';
import { structurePreviewQuery, structureQuery, useSaveStructure } from '../api';
import { Section, useDebounced } from './common';

type Template = Schemas['TaskTemplate'];
const GRID = 'minmax(220px,2fr) minmax(140px,1fr) minmax(120px,1fr) 110px 40px';
const SEEN_GRID = '110px minmax(220px,2fr) minmax(120px,1fr) minmax(160px,1.4fr)';
const HEAD = { display: 'grid', gap: 12, padding: '8px 20px', background: 'var(--surface-hover)', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)' } as const;
const ROW = { display: 'grid', gap: 12, alignItems: 'center', padding: '8px 20px', borderTop: '1px solid var(--border-subtle)' } as const;

function swap<T>(xs: T[], i: number, j: number): T[] {
  const ys = [...xs];
  [ys[i], ys[j]] = [ys[j], ys[i]];
  return ys;
}

export function StructureTab() {
  const q = useQuery(structureQuery);
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  return <StructureForm v={q.data} />;
}

function StructureForm({ v }: { v: Schemas['StructureView'] }) {
  const toast = useToast();
  const save = useSaveStructure();
  const [phases, setPhases] = useState<string[]>(v.phases);
  const [tasks, setTasks] = useState<Template[]>(v.tasks);
  const body = useDebounced({ phases, tasks }, 250);
  const seen = useQuery(structurePreviewQuery(body)).data?.seen ?? v.seen;
  const setTask = (i: number, patch: Partial<Template>) => setTasks((ts) => ts.map((t, j) => (j === i ? { ...t, ...patch } : t)));
  const phaseOpts = [{ value: '', label: '— Choose phase —' }, ...phases.filter(Boolean).map((p) => ({ value: p, label: p }))];
  const unmatched = seen.filter((s) => s.template == null).length;
  return (
    <>
      <Section title="Phases" sub="Project phases in order. Overview and Effort list phases in this order; phases found on tickets but not listed here come last.">
        {phases.map((p, i) => (
          <div key={i} style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <TextField aria-label={`Phase ${i + 1}`} value={p} onChange={(val) => setPhases((ps) => ps.map((x, j) => (j === i ? val : x)))} style={{ flex: 1 }} />
            <IconButton icon="arrow-up" label={`Move phase ${i + 1} up`} appearance="subtle" disabled={i === 0} onClick={() => setPhases((ps) => swap(ps, i, i - 1))} />
            <IconButton icon="arrow-down" label={`Move phase ${i + 1} down`} appearance="subtle" disabled={i === phases.length - 1} onClick={() => setPhases((ps) => swap(ps, i, i + 1))} />
            <IconButton icon="trash-2" label={`Remove phase ${i + 1}`} appearance="subtle" onClick={() => setPhases((ps) => ps.filter((_, j) => j !== i))} />
          </div>
        ))}
        <div><Button appearance="secondary" iconBefore="plus" onClick={() => setPhases((ps) => [...ps, ''])}>Add phase</Button></div>
      </Section>
      <Section pad={false} title="Tasks" sub={<>The fixed set of Tasks under every Epic. The Task name is matched against the whole Jira Task Summary, ignoring case; <code>*</code> matches any text. Prefix is what <code>{'{prefix}'}</code> stands for in that Task's sub-task names. Turn the workflow off for Tasks whose sub-tasks are not functions (Q&amp;A, WR, CR…).</>}>
        <div style={{ overflowX: 'auto' }}>
          <div style={{ minWidth: 760 }}>
            <div style={{ ...HEAD, gridTemplateColumns: GRID }}><span>Task name</span><span>Phase</span><span>Prefix</span><span>Workflow</span><span /></div>
            {tasks.map((t, i) => (
              <div key={i} style={{ ...ROW, gridTemplateColumns: GRID }}>
                <TextField aria-label={`Task ${i + 1} name`} placeholder="e.g. FUT create test case" value={t.match} onChange={(val) => setTask(i, { match: val })} />
                <Select aria-label={`Task ${i + 1} phase`} value={t.phase} options={phaseOpts} onChange={(val) => setTask(i, { phase: val })} />
                <TextField mono aria-label={`Task ${i + 1} prefix`} value={t.prefix} onChange={(val) => setTask(i, { prefix: val })} />
                <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
                  <input type="checkbox" checked={t.workflow} onChange={(e) => setTask(i, { workflow: e.target.checked })} /> 5 steps
                </label>
                <IconButton icon="trash-2" label={`Remove task ${i + 1}`} appearance="subtle" onClick={() => setTasks((ts) => ts.filter((_, j) => j !== i))} />
              </div>
            ))}
            <div style={{ padding: '12px 20px', borderTop: '1px solid var(--border-subtle)' }}>
              <Button appearance="secondary" iconBefore="plus" onClick={() => setTasks((ts) => [...ts, { match: '', phase: '', prefix: '', workflow: true }])}>Add task</Button>
            </div>
          </div>
        </div>
      </Section>
      <Section pad={false} title="Tasks in Jira" sub={`Tasks of the tracked Epics, classified with the settings above before you save. ${unmatched} without a matching task: their phase comes from a leading [Phase] in the Summary.`}>
        <div style={{ overflowX: 'auto' }}>
          <div style={{ minWidth: 640 }}>
            <div style={{ ...HEAD, gridTemplateColumns: SEEN_GRID }}><span>Key</span><span>Summary</span><span>Phase</span><span>Matched task</span></div>
            {seen.map((s) => (
              <div key={s.key} style={{ ...ROW, gridTemplateColumns: SEEN_GRID, fontSize: 12 }}>
                <span className="mono">{s.key}</span>
                <span className="heading">{s.summary}</span>
                <span>{s.phase || '—'}</span>
                {s.template == null
                  ? <span style={{ fontWeight: 600, color: 'var(--hl-amber-fg)' }}>No match</span>
                  : <span>{tasks[s.template]?.match}</span>}
              </div>
            ))}
          </div>
        </div>
      </Section>
      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Button disabled={save.isPending} onClick={() => save.mutate({ phases, tasks }, {
          onSuccess: () => toast('Phases & tasks saved · synced'), onError: (e) => toast(errorMessage(e), true),
        })}>Save</Button>
      </div>
    </>
  );
}
