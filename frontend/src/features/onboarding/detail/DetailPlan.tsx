import { useQuery } from '@tanstack/react-query';
import { useRef, useState, type ChangeEvent } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { membersQuery, useCan } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button, Tabs } from '../../../shared/ds';
import { downloadCsv } from '../../../shared/format/csv';
import { useToast } from '../../../shared/state/toast';
import { onboardingQuery } from '../api';
import { AddRowDialog } from './AddRowDialog';
import { EffortDays } from './EffortDays';
import { ImportDialog } from './ImportDialog';
import { MoreMenu, type MenuItem } from './MoreMenu';
import { PlanSummary } from './PlanSummary';
import { StepsTable, type Slot } from './StepsTable';
import { jiraCsv, planCsv } from './csv';
import {
  detailTemplateUrl, useAutoAllocate, useCheckPlan, useCheckResult, useClearPlan, useImportPreview, useSaveDraft, withCheck, type DetailPlanView,
  type DraftIn, type ImportLine,
} from './api';

/** A person who can be given a row: a member of the anken, with their role. */
export interface Person { id: string; name: string; role?: string | null }

type Row = Schemas['EstimationRow'];
type View = 'lines' | 'days';

/**
 * Who does each function and the dates that follow, checked against the master schedule. Auto allocate plans and saves the
 * dates; what is changed after stays as it is until the next one, and Check shows what it does to each member's hours. The
 * push to Jira sits in the step's action bar (PushControls).
 *
 * @param checked show what stands before the push (problems, budgets, days over the allocation) without being asked: once
 *                the PIM saved, tried to finish or reviewed the push, not on the first visit
 */
export function DetailPlan({ epic, v, people, checked = true }: { epic: string; v: DetailPlanView; people: Person[]; checked?: boolean }) {
  const admin = useCan('planning', 'write');
  const toast = useToast();
  const name = (id: string) => people.find((p) => p.id === id)?.name ?? id;
  const [view, setView] = useState<View>('lines');
  const [adding, setAdding] = useState<'function' | 'task' | null>(null);
  const [clearing, setClearing] = useState(false);
  const [imported, setImported] = useState<{ file: string; lines: ImportLine[] } | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const preview = useImportPreview(epic);
  const save = useSaveDraft(epic);
  const auto = useAutoAllocate(epic);
  const clear = useClearPlan(epic);
  const check = useCheckPlan(epic);
  const shown = withCheck(v, useCheckResult(epic));
  const busy = auto.isPending || save.isPending || clear.isPending || preview.isPending || check.isPending;
  const labels = useQuery(onboardingQuery(epic)).data?.labels ?? [];
  const members = useQuery(membersQuery(epic)).data ?? [];
  const open = v.rows.filter((r) => !r.pushed_at);
  // a slot Auto allocate would fill: Auto allocate is the next thing to do while one is left
  const empty = open.some((r) => !r.ba_id || (r.steps && (!r.bal_id || (r.steps > 3 && !r.jm_id))));

  const clearPlan = (keepInput: boolean) => clear.mutate(keepInput, {
    onSuccess: () => toast(keepInput ? 'Auto allocate\'s picks cleared · what you set by hand stays' : 'Plan cleared'),
    onError: (e) => toast(errorMessage(e), true),
  });
  const label = (id?: string | null) => (id ? name(id) : '');
  const email = (id?: string | null) => (id ? members.find((m) => m.id === id)?.email || name(id) : '');
  const exportPlan = () => {
    const c = planCsv(v, label);
    downloadCsv(`detail-plan-${epic}.csv`, c.header, c.rows);
    toast(`Exported ${c.rows.length} sub-tasks`);
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
    ...(admin && open.length > 0 ? [
      { label: 'Clear, keep my input', icon: 'eraser' as const, disabled: busy,
        title: 'Empties the BA / BAL / JM Auto allocate filled. People you picked, dates and efforts you set and removed lines stay: Auto allocate then plans again around them.',
        onClick: () => clearPlan(true) },
      { label: 'Clear all', icon: 'trash-2' as const, disabled: busy, onClick: () => setClearing(true) },
      'sep' as const,
    ] : []),
    { label: 'Download template', icon: 'file-down', disabled: open.length === 0,
      title: 'The sub-tasks as an Excel file: change BA / BAL / JM, efforts and dates, then import it back', onClick: downloadTemplate },
    ...(admin ? [{ label: 'Import Excel / CSV', icon: 'file-up' as const, disabled: busy || open.length === 0,
      title: 'Reads a filled template; you review what changes before it is applied', onClick: () => fileInput.current?.click() }] : []),
    'sep' as const,
    { label: 'Export plan', icon: 'download', disabled: open.length === 0, onClick: exportPlan },
    { label: 'Export for Jira', icon: 'file-spreadsheet', disabled: open.length === 0, title: 'The sub-tasks the push would create, for Jira › Import work items from CSV', onClick: exportJira },
  ];

  return (
    <>
      <PlanSummary epic={epic} v={shown} name={name} checked={checked} />
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, flexWrap: 'wrap' }}>
        <Tabs<View> value={view} onChange={setView} style={{ borderBottom: 0 }}
          tabs={[{ value: 'lines', label: 'Sub-tasks' }, { value: 'days', label: 'Effort per day' }]} />
        <span className="spacer" />
        <span style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', paddingBottom: 4 }}>
          {admin && <Button appearance="secondary" size="sm" iconBefore="plus" disabled={v.tasks.length === 0} onClick={() => setAdding('function')}>Add function</Button>}
          {admin && <Button appearance="secondary" size="sm" iconBefore="plus" disabled={v.tasks.length === 0} onClick={() => setAdding('task')}>Add task</Button>}
          <MoreMenu items={more} />
          {admin && open.length > 0 && (
            <Button appearance="secondary" size="sm" iconBefore="scan-search" disabled={busy}
              title="Checks each member's hours per day for the dates as they stand. Nothing is planned again or saved."
              onClick={() => check.mutate(undefined, {
                onSuccess: (c) => toast(c.affected.length ? `Checked · ${c.affected.length} sub-task${c.affected.length === 1 ? '' : 's'} to look at` : 'Checked · nothing over'),
                onError: (e) => toast(errorMessage(e), true),
              })}>
              {check.isPending ? 'Checking…' : 'Check'}
            </Button>
          )}
          {admin && open.length > 0 && (
            <Button appearance={empty ? 'primary' : 'secondary'} size="sm" iconBefore="users" disabled={busy}
              title="Fills the empty BA / BAL / JM of each function from the phase's team in the master schedule, keeping members within their hours in the phase, phases on time and work evenly shared, then plans every function again. People already picked and dates or efforts set by hand stay; the plan stays as it is until the next Auto allocate."
              onClick={() => auto.mutate(undefined, { onSuccess: () => toast('Allocated and planned'), onError: (e) => toast(errorMessage(e), true) })}>
              {auto.isPending ? 'Allocating…' : 'Auto allocate'}
            </Button>
          )}
        </span>
      </div>
      {view === 'lines'
        ? <StepsTable epic={epic} v={v} people={people} admin={admin} assign={assign} swap={swap} affected={shown.affected} />
        : shown.days.length ? <EffortDays v={shown} name={name} />
          : <div className="card" style={{ padding: 16, color: 'var(--text-muted)' }}>Nothing is planned yet: Auto allocate plans every function.</div>}
      <input ref={fileInput} type="file" accept=".xlsx,.csv" aria-label="Import file" hidden onChange={onFile} />
      {imported && <ImportDialog epic={epic} file={imported.file} lines={imported.lines} name={label} close={() => setImported(null)} />}
      {adding && <AddRowDialog epic={epic} kind={adding} tasks={v.tasks} close={() => setAdding(null)} />}
      {clearing && (
        <div className="scrim" onClick={() => setClearing(false)}>
          <div className="dialog" role="dialog" aria-modal="true" aria-label="Clear the plan" onClick={(e) => e.stopPropagation()} style={{ width: 'min(520px,100%)' }}>
            <span className="dialog-title">Clear the whole plan?</span>
            <span style={{ fontSize: 13 }}>
              Every BA, BAL and JM of the rows not in Jira yet is emptied, also the people you picked; the dates and efforts set by
              hand go, the removed lines come back and the planned dates go too, until the next Auto allocate. The rows and their
              estimation stay.
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
