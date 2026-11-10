import { useQuery } from '@tanstack/react-query';
import { useRef, useState, type ChangeEvent } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { Button, IconButton, Select, TextField } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { Loading, QueryError } from '../../../shared/ui/states';
import { structurePreviewQuery, structureQuery, useImportStructure, useSaveStructure } from '../api';
import { AnkenPicker, useSettingsTarget } from './AnkenPicker';
import { Section, useDebounced } from './common';

type TaskType = NonNullable<Schemas['TaskTemplateIn']['type']>;
type Template = Omit<Schemas['TaskTemplate'], 'type'> & { type: TaskType };
const TYPES: { value: TaskType; label: string }[] = [
  { value: '', label: 'Task' }, { value: 'qna', label: 'QnA' }, { value: 'bug', label: 'Bug' }, { value: 'work_request', label: 'WorkRequest' },
];
const GRID = 'minmax(220px,2fr) minmax(140px,1fr) minmax(120px,1fr) 140px 110px 40px';
const SEEN_GRID = '110px minmax(220px,2fr) minmax(120px,1fr) minmax(160px,1.4fr)';
const HEAD = { display: 'grid', gap: 12, padding: '8px 20px', background: 'var(--surface-hover)', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)' } as const;
const ROW = { display: 'grid', gap: 12, alignItems: 'center', padding: '8px 20px', borderTop: '1px solid var(--border-subtle)' } as const;

/** How Task names (and imported phases) are compared: trimmed, lower case, runs of whitespace as one space. */
const norm = (s: string) => s.trim().toLowerCase().replace(/\s+/g, ' ');

/**
 * The form with the imported rows merged in: a row with a Task name already on the form updates it, any other is added; a
 * phase not on the form is added at the end. {@code added} holds the names of what was added, for the highlight.
 */
function merge(phases: string[], tasks: Template[], rows: Schemas['StructureImportRow'][]) {
  const ps = [...phases];
  const ts = [...tasks];
  const added = { phases: [] as string[], tasks: [] as string[] };
  let updated = 0;
  for (const r of rows) {
    let phase = ps.find((p) => norm(p) === norm(r.phase));
    if (phase === undefined) {
      phase = r.phase;
      ps.push(phase);
      added.phases.push(norm(phase));
    }
    const t = { phase, prefix: r.prefix, type: (TYPES.find((x) => x.value === r.type)?.value ?? ''), workflow: r.workflow };
    const i = ts.findIndex((x) => norm(x.match) === norm(r.match));
    if (i < 0) {
      ts.push({ match: r.match, ...t });
      added.tasks.push(norm(r.match));
    } else {
      ts[i] = { ...ts[i], ...t };
      if (!added.tasks.includes(norm(r.match))) updated++;
    }
  }
  return { phases: ps, tasks: ts, added, updated };
}

function swap<T>(xs: T[], i: number, j: number): T[] {
  const ys = [...xs];
  [ys[i], ys[j]] = [ys[j], ys[i]];
  return ys;
}

export function StructureTab() {
  const t = useSettingsTarget();
  const anken = t.status === 'ready' ? t.anken : null;
  const [rev, setRev] = useState(0);
  const q = useQuery({ ...structureQuery(anken), enabled: t.status === 'ready' });
  if (t.status === 'pending') return <Loading />;
  if (t.status === 'error') return <QueryError error={t.error} />;
  return (
    <>
      <AnkenPicker anken={anken} untracked={t.untracked} onCopied={() => setRev((r) => r + 1)} />
      {q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : <StructureForm key={`${anken ?? ''}:${rev}`} anken={anken} v={q.data} />}
    </>
  );
}

function StructureForm({ v, anken }: { v: Schemas['StructureView']; anken: string | null }) {
  const toast = useToast();
  const save = useSaveStructure(anken);
  const importFile = useImportStructure(anken);
  const fileInput = useRef<HTMLInputElement>(null);
  const [phases, setPhases] = useState<string[]>(v.phases);
  const [daily, setDaily] = useState<string[]>(v.daily);
  const [tasks, setTasks] = useState<Template[]>(v.tasks.map((t) => ({ ...t, type: (TYPES.find((x) => x.value === t.type)?.value ?? '') })));
  const [fresh, setFresh] = useState<{ phases: string[]; tasks: string[] }>({ phases: [], tasks: [] }); // added by an import, until saved
  const onFile = (e: ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    e.target.value = '';
    if (!f) return;
    importFile.mutate(f, {
      onSuccess: (rows) => {
        const ok = rows.filter((r) => r.errors.length === 0);
        const bad = rows.filter((r) => r.errors.length > 0);
        const m = merge(phases, tasks, ok);
        setPhases(m.phases);
        setTasks(m.tasks);
        setFresh((x) => ({ phases: [...x.phases, ...m.added.phases], tasks: [...x.tasks, ...m.added.tasks] }));
        const skipped = bad.length ? ` · skipped ${bad.map((r) => `line ${r.line} (${r.errors.join(', ')})`).join('; ')}` : '';
        toast(`${f.name}: ${m.added.tasks.length} added · ${m.updated} updated${skipped}. Check the matches, then Save.`, ok.length === 0);
      },
      onError: (err) => toast(errorMessage(err), true),
    });
  };
  const body = useDebounced({ phases, tasks }, 250);
  const seen = useQuery(structurePreviewQuery(anken, body)).data?.seen ?? v.seen;
  const setTask = (i: number, patch: Partial<Template>) => setTasks((ts) => ts.map((t, j) => (j === i ? { ...t, ...patch } : t)));
  const phaseOpts = [{ value: '', label: '— Choose phase —' }, ...phases.filter(Boolean).map((p) => ({ value: p, label: p }))];
  const unmatched = seen.filter((s) => s.template == null).length;
  return (
    <>
      <Section title="Phases" sub="Project phases in order. Overview and Effort list phases in this order; phases found on tickets but not listed here come last. Ticked phases are the tabs of the Daily Overview; none ticked: the phases that have started.">
        {phases.map((p, i) => (
          <div key={i} data-imported={fresh.phases.includes(norm(p)) || undefined}
            style={{ display: 'flex', gap: 8, alignItems: 'center', ...(fresh.phases.includes(norm(p)) && { background: 'var(--hl-ok)', borderRadius: 10, padding: 4 }) }}>
            <TextField aria-label={`Phase ${i + 1}`} value={p} onChange={(val) => {
              setDaily((d) => d.map((x) => (x === p ? val : x)));
              setPhases((ps) => ps.map((x, j) => (j === i ? val : x)));
            }} style={{ flex: 1 }} />
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
              <input type="checkbox" aria-label={`Show Phase ${i + 1} on the Daily Overview`} checked={daily.includes(p)}
                onChange={(e) => setDaily((d) => (e.target.checked ? [...d, p] : d.filter((x) => x !== p)))} /> Daily
            </label>
            <IconButton icon="arrow-up" label={`Move phase ${i + 1} up`} appearance="subtle" disabled={i === 0} onClick={() => setPhases((ps) => swap(ps, i, i - 1))} />
            <IconButton icon="arrow-down" label={`Move phase ${i + 1} down`} appearance="subtle" disabled={i === phases.length - 1} onClick={() => setPhases((ps) => swap(ps, i, i + 1))} />
            <IconButton icon="trash-2" label={`Remove phase ${i + 1}`} appearance="subtle" onClick={() => { setDaily((d) => d.filter((x) => x !== p)); setPhases((ps) => ps.filter((_, j) => j !== i)); }} />
          </div>
        ))}
        <div><Button appearance="secondary" iconBefore="plus" onClick={() => setPhases((ps) => [...ps, ''])}>Add phase</Button></div>
      </Section>
      <Section pad={false} title="Tasks" sub={<>The fixed set of Tasks under every Epic. The Task name is matched against the whole Jira Task Summary, ignoring case; <code>*</code> matches any text. Prefix is what <code>{'{prefix}'}</code> stands for in that Task's sub-task names. Turn the workflow off for Tasks whose sub-tasks are not functions (Q&amp;A, WR, CR…). Type marks the Tasks whose sub-tasks the Daily Overview lists as QnA, Bug or WorkRequest.</>}>
        <div style={{ overflowX: 'auto' }}>
          <div style={{ minWidth: 900 }}>
            <div style={{ ...HEAD, gridTemplateColumns: GRID }}><span>Task name</span><span>Phase</span><span>Prefix</span><span>Type</span><span>Workflow</span><span /></div>
            {tasks.map((t, i) => (
              <div key={i} data-imported={fresh.tasks.includes(norm(t.match)) || undefined}
                style={{ ...ROW, gridTemplateColumns: GRID, ...(fresh.tasks.includes(norm(t.match)) && { background: 'var(--hl-ok)' }) }}>
                <TextField aria-label={`Task ${i + 1} name`} placeholder="e.g. FUT create test case" value={t.match} onChange={(val) => setTask(i, { match: val })} />
                <Select aria-label={`Task ${i + 1} phase`} value={t.phase} options={phaseOpts} onChange={(val) => setTask(i, { phase: val })} />
                <TextField mono aria-label={`Task ${i + 1} prefix`} value={t.prefix} onChange={(val) => setTask(i, { prefix: val })} />
                <Select aria-label={`Task ${i + 1} type`} value={t.type} options={TYPES} onChange={(val) => setTask(i, { type: val as TaskType })} />
                <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
                  <input type="checkbox" checked={t.workflow} onChange={(e) => setTask(i, { workflow: e.target.checked })} /> 5 steps
                </label>
                <IconButton icon="trash-2" label={`Remove task ${i + 1}`} appearance="subtle" onClick={() => setTasks((ts) => ts.filter((_, j) => j !== i))} />
              </div>
            ))}
            <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8, padding: '12px 20px', borderTop: '1px solid var(--border-subtle)' }}>
              <Button appearance="secondary" iconBefore="plus" onClick={() => setTasks((ts) => [...ts, { match: '', phase: '', prefix: '', workflow: true, type: '' }])}>Add task</Button>
              <Button appearance="secondary" iconBefore="file-up" disabled={importFile.isPending} onClick={() => fileInput.current?.click()}>
                {importFile.isPending ? 'Reading file…' : 'Import Excel / CSV'}
              </Button>
              <input ref={fileInput} type="file" accept=".xlsx,.csv" aria-label="Phases and tasks file" onChange={onFile} style={{ display: 'none' }} />
              <span className="eyebrow">Columns: Phase, Task name, Prefix, Type (Task, QnA, Bug, WorkRequest), Workflow (not read). Rows with a Task name already here update it.</span>
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
        <Button disabled={save.isPending} onClick={() => save.mutate({ phases, tasks, daily }, {
          onSuccess: () => { setFresh({ phases: [], tasks: [] }); toast('Phases & tasks saved · synced'); }, onError: (e) => toast(errorMessage(e), true),
        })}>Save</Button>
      </div>
    </>
  );
}
