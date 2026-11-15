import { useQuery } from '@tanstack/react-query';
import { useRef, useState, type ChangeEvent } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { membersQuery, useCan, useMemberMap } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button } from '../../../shared/ds';
import { downloadCsv } from '../../../shared/format/csv';
import { ymd } from '../../../shared/format/dates';
import { useToast } from '../../../shared/state/toast';
import { onboardingQuery } from '../../onboarding';
import { AddRowDialog } from './AddRowDialog';
import { EffortDays } from './EffortDays';
import { ImportDialog } from './ImportDialog';
import { MoreMenu, type MenuItem } from './MoreMenu';
import { PlanSummary } from './PlanSummary';
import { PlanMatrix, type Slot } from './PlanMatrix';
import { jiraCsv, planCsv } from './csv';
import {
  detailTemplateUrl, useAutoAllocate, useClearPlan, useImportPreview, useSaveDraft, useSaveVersion, type DetailPlanView, type DraftIn, type ImportLine,
} from './api';

/** A person who can be given a row: a member of the anken, with their role. */
export interface Person { id: string; name: string; role?: string | null }

/** A name by member id: the anken's people first, then every member (a PIC or an assignee outside the anken), else the id. */
export function usePersonName(people: Person[]): (id: string) => string {
  const members = useMemberMap();
  return (id) => people.find((p) => p.id === id)?.name ?? members[id]?.name ?? id;
}

type Row = Schemas['EstimationRow'];

/** A saved time (ISO instant) as the viewer's yyyy/MM/dd HH:mm. */
const at = (iso: string) => {
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${ymd(`${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`)} ${p(d.getHours())}:${p(d.getMinutes())}`;
};

/**
 * The plan of the anken on one screen: each member's effort per day on top, then the plan matrix (every line × day) with who
 * does each function. Hours typed in a day are saved at once and the effort per day follows; Auto allocate fills what is not
 * planned yet around them. Save version keeps a snapshot of the whole plan. The push to Jira sits in the step's action bar
 * (PushControls).
 *
 * @param checked show what stands before the push (problems, budgets, days over the allocation) without being asked: once
 *                the PIM saved, tried to finish or reviewed the push, not on the first visit
 */
export function DetailPlan({ epic, v, people, checked = true }: {
  epic: string; v: DetailPlanView; people: Person[]; checked?: boolean;
}) {
  const admin = useCan('planning', 'write');
  const toast = useToast();
  const name = usePersonName(people);
  const [adding, setAdding] = useState<'function' | 'task' | null>(null);
  const [clearing, setClearing] = useState(false);
  const [imported, setImported] = useState<{ file: string; lines: ImportLine[] } | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const preview = useImportPreview(epic);
  const save = useSaveDraft(epic);
  const auto = useAutoAllocate(epic);
  const clear = useClearPlan(epic);
  const version = useSaveVersion(epic);
  const busy = auto.isPending || save.isPending || clear.isPending || preview.isPending || version.isPending;
  const labels = useQuery(onboardingQuery(epic)).data?.labels ?? [];
  const members = useQuery(membersQuery(epic)).data ?? [];
  const open = v.rows.filter((r) => !r.pushed_at);
  const shown = v.lines.filter((l) => !l.removed);
  // Clear empties the rows not in Jira yet (and brings their removed lines back) and drops the Jira changes not pushed
  const clearable = open.length > 0 || v.lines.some((l) => l.issue_key != null && l.pending);
  // a slot Auto allocate would fill: Auto allocate is the next thing to do while one is left
  const empty = open.some((r) => !r.ba_id || (r.steps && (!r.bal_id || (r.steps > 3 && !r.jm_id))));

  const clearPlan = (keepInput: boolean) => clear.mutate(keepInput, {
    onSuccess: () => toast(keepInput ? 'What Auto allocate filled is cleared · what you typed or picked stays' : 'Plan cleared'),
    onError: (e) => toast(errorMessage(e), true),
  });
  const label = (id?: string | null) => (id ? name(id) : '');
  const email = (id?: string | null) => (id ? members.find((m) => m.id === id)?.email || name(id) : '');
  const exportPlan = () => {
    const c = planCsv(v, label);
    downloadCsv(`detail-plan-${epic}.csv`, c.header, c.rows);
    toast(`Exported ${c.rows.length} lines`);
  };
  const exportJira = () => {
    const c = jiraCsv(v, email, labels);
    downloadCsv(`jira-subtasks-${epic}.csv`, c.header, c.rows);
    toast(`Exported ${c.rows.length} sub-tasks · Jira › Import work items from CSV; the rows stay unpushed here`);
  };
  const downloadTemplate = () => {
    const a = document.createElement('a');
    a.href = detailTemplateUrl(epic);
    a.download = `detail-plan-${epic}.xlsx`;
    a.click();
  };
  const onFile = (e: ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (f) preview.mutate(f, { onSuccess: (lines) => setImported({ file: f.name, lines }), onError: (err) => toast(errorMessage(err), true) });
    e.target.value = '';
  };
  const assigns = (rows: Row[]) => rows.map((y) => ({ row_id: y.id, ba_id: y.ba_id ?? null, bal_id: y.bal_id ?? null, jm_id: y.jm_id ?? null }));
  const draft = (p: Partial<DraftIn>) => save.mutate({ order: open.map((r) => r.id), assigns: assigns(open), ...p }, { onError: (e) => toast(errorMessage(e), true) });
  const assign = (r: Row, slot: Slot, id: string | null) => draft({ assigns: assigns(open.map((x) => (x.id === r.id ? { ...x, [slot]: id } : x))) });
  const swap = (a: number, b: number) => draft({ order: open.map((r) => (r.id === a ? b : r.id === b ? a : r.id)) });

  const more: (MenuItem | 'sep')[] = [
    ...(admin && clearable ? [
      { label: 'Clear, keep my input', icon: 'eraser' as const, disabled: busy,
        title: 'Empties the hours and the BA / BAL / JM Auto allocate filled. Hours you typed, people you picked, efforts you set and removed lines stay: Auto allocate then fills again around them.',
        onClick: () => clearPlan(true) },
      { label: 'Clear all', icon: 'trash-2' as const, disabled: busy, onClick: () => setClearing(true) },
      'sep' as const,
    ] : []),
    { label: 'Download template', icon: 'file-down', disabled: open.length === 0,
      title: 'The sub-tasks not in Jira yet as an Excel file, a column per day: change BA / BAL / JM, efforts and hours, then import it back', onClick: downloadTemplate },
    ...(admin ? [{ label: 'Import Excel / CSV', icon: 'file-up' as const, disabled: busy || open.length === 0,
      title: 'Reads a filled template; you review what changes before it is applied', onClick: () => fileInput.current?.click() }] : []),
    'sep' as const,
    { label: 'Export plan', icon: 'download', disabled: shown.length === 0, title: 'Every line with its hours per day, as CSV', onClick: exportPlan },
    { label: 'Export for Jira', icon: 'file-spreadsheet', disabled: open.length === 0, title: 'The sub-tasks the push would create, for Jira › Import work items from CSV', onClick: exportJira },
  ];

  return (
    <>
      <PlanSummary epic={epic} v={v} name={name} checked={checked} />
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        {admin && <Button appearance="secondary" size="sm" iconBefore="plus" disabled={v.tasks.length === 0} onClick={() => setAdding('function')}>Add function</Button>}
        {admin && <Button appearance="secondary" size="sm" iconBefore="plus" disabled={v.tasks.length === 0} onClick={() => setAdding('task')}>Add task</Button>}
        <MoreMenu items={more} />
        <span className="spacer" />
        {v.last_version_at && <span className="eyebrow">{`Last version ${at(v.last_version_at)}`}</span>}
        {admin && (
          <Button appearance="secondary" size="sm" iconBefore="save" disabled={busy}
            title="Keeps a snapshot of the plan. The first one is the anken's Baseline plan; every line must be in Jira, pushed and up to date"
            onClick={() => version.mutate(undefined, { onSuccess: () => toast('Version saved'), onError: (e) => toast(errorMessage(e), true) })}>
            {version.isPending ? 'Saving…' : 'Save version'}
          </Button>
        )}
        {admin && shown.length > 0 && (
          <Button appearance={empty ? 'primary' : 'secondary'} size="sm" iconBefore="users" disabled={busy}
            title="Fills the empty BA / BAL / JM of each function from the phase's team in the master schedule, then fills what is not planned yet of each line, keeping members within their hours and steps in order. Hours you typed and people you picked stay."
            onClick={() => auto.mutate(undefined, { onSuccess: () => toast('Allocated and planned'), onError: (e) => toast(errorMessage(e), true) })}>
            {auto.isPending ? 'Allocating…' : 'Auto allocate'}
          </Button>
        )}
      </div>
      {v.days.length ? <EffortDays v={v} name={name} />
        : <div className="card" style={{ padding: 16, color: 'var(--text-muted)' }}>Nothing is planned yet: type hours in a day, or Auto allocate.</div>}
      <PlanMatrix epic={epic} v={v} people={people} admin={admin} assign={assign} swap={swap} />
      <input ref={fileInput} type="file" accept=".xlsx,.csv" aria-label="Import file" hidden onChange={onFile} />
      {imported && <ImportDialog epic={epic} file={imported.file} lines={imported.lines} name={label} close={() => setImported(null)} />}
      {adding && <AddRowDialog epic={epic} kind={adding} tasks={v.tasks} close={() => setAdding(null)} />}
      {clearing && (
        <div className="scrim" onClick={() => setClearing(false)}>
          <div className="dialog" role="dialog" aria-modal="true" aria-label="Clear the plan" onClick={(e) => e.stopPropagation()} style={{ width: 'min(520px,100%)' }}>
            <span className="dialog-title">Clear the whole plan?</span>
            <span style={{ fontSize: 13 }}>
              Every hour planned on the rows not in Jira yet is emptied, also the hours you typed, and every BA, BAL and JM, also the
              people you picked; the efforts set by hand go and the removed lines come back. Every change to a sub-task in Jira not
              pushed yet is dropped: they show Jira's plan again. The rows and their estimation stay.
            </span>
            <div style={{ display: 'flex', gap: 8 }}>
              <span className="spacer" />
              <Button appearance="subtle" onClick={() => setClearing(false)}>Cancel</Button>
              <Button onClick={() => { setClearing(false); clearPlan(false); }}>Clear all</Button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
