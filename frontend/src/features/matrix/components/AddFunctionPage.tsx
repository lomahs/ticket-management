import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { useEffect, useMemo, useState } from 'react';
import { ApiError, errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { useAdminJiraWrite, useMemberMap } from '../../../shared/api/queries';
import { Button, Icon, Select, TextField } from '../../../shared/ds';
import { dm, f1 } from '../../../shared/format/dates';
import { STEPS } from '../../../shared/format/labels';
import { useToast } from '../../../shared/state/toast';
import { Avatar, av, Seg } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { functionPreviewQuery, matrixQuery, useCreateFunction } from '../api';
import { MatrixHeader } from './MatrixHeader';

const route = getRouteApi('/app/anken/$epic/matrix/new');
const COLS = 'minmax(220px,1fr) 90px 56px 150px 150px';
const WEIGHTS = [1, 0.25, 0.3, 0.25, 0.2]; // display only: the server computes the real estimates

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

export function AddFunctionPage() {
  const { epic } = route.useParams();
  const matrix = useQuery(matrixQuery(epic));
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, minWidth: 0 }}>
      <MatrixHeader epic={epic} title={matrix.data?.title} phase={matrix.data?.phase} tab="new" />
      {matrix.isPending ? <Loading /> : matrix.isError ? <QueryError error={matrix.error} /> : <AddFunction epic={epic} m={matrix.data} />}
    </div>
  );
}

function AddFunction({ epic, m }: { epic: string; m: Schemas['MatrixView'] }) {
  const members = useMemberMap();
  const navigate = route.useNavigate();
  const toast = useToast();
  const { ok: canWrite, off } = useAdminJiraWrite();
  const create = useCreateFunction(epic);
  const byRole = (r: string) => Object.values(members).filter((x) => x.role === r && m.member_ids.includes(x.id)).map((x) => ({ value: x.id, label: x.name }));
  const baOpts = byRole('BA'), balOpts = byRole('BAL'), jmOpts = byRole('JM');
  const workflowParents = m.parents.filter((p) => p.workflow);
  const defaultParent = workflowParents.find((p) => p.key === m.task_keys[0]) ?? workflowParents[0];
  const [form, setForm] = useState({ parent: defaultParent?.key ?? '', name: '', ba: '', bal: '', jm: '', hours: '8' });
  const [steps, setSteps] = useState<3 | 5>(5);
  const five = steps === 5;
  const [overrides, setOverrides] = useState<({ start?: string; end?: string } | null)[]>([null, null, null, null, null]);
  const [nameErr, setNameErr] = useState<string>();
  const ba = form.ba || baOpts[0]?.value || '', bal = form.bal || balOpts[0]?.value || '', jm = form.jm || jmOpts[0]?.value || '';
  const hours = parseFloat(form.hours);
  const draft = useMemo<Schemas['FunctionDraftIn']>(() => ({
    parent_key: form.parent, name: form.name, ba, bal, jm: five ? jm : null, create_hours: hours, overrides: overrides.slice(0, steps), steps,
  }), [form.parent, form.name, ba, bal, jm, hours, overrides, steps, five]);
  const debounced = useDebounced(draft, 250);
  const ready = !!(debounced.parent_key && debounced.ba && debounced.bal && (debounced.steps === 3 || debounced.jm) && debounced.create_hours > 0);
  const preview = useQuery(functionPreviewQuery(epic, debounced, ready));
  const set = (k: keyof typeof form) => (v: string) => setForm((f) => ({ ...f, [k]: v }));
  const setDate = (i: number, k: 'start' | 'end', v: string) => setOverrides((o) => o.map((x, j) => (j === i ? { ...(x ?? {}), [k]: v || undefined } : x)));

  const submit = () => {
    if (!form.name.trim()) { setNameErr('Function name is required'); return; }
    create.mutate({ ...draft, name: form.name.trim() }, {
      onSuccess: (r) => {
        toast(`Written to Jira · created ${r.keys.length} sub-tasks for ${preview.data?.function_id || form.name.trim()}`);
        navigate({ to: '/anken/$epic/matrix', params: { epic }, search: { tasks: form.parent } });
      },
      onError: (e) => (e instanceof ApiError && e.field('name') ? setNameErr(e.field('name')) : toast(errorMessage(e), true)),
    });
  };
  const disabled = !canWrite || !ready || !form.name.trim() || create.isPending;

  return (
    <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: 16, fontSize: 13, padding: '20px 22px' }}>
      <div>
        <div style={{ font: '700 17px/1.3 var(--font-display)', color: 'var(--text-heading)' }}>Add new function</div>
        <div className="eyebrow">Creates {steps} sequential sub-tasks under the chosen Task, named by the name rule in Settings</div>
      </div>
      <Seg outline items={[['5', '5 steps'], ['3', '3 steps']]} value={String(steps)} onPick={(v) => setSteps(v === '3' ? 3 : 5)} />
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(260px,1fr))', gap: 12 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <Select label="Parent ticket" options={workflowParents.map((p) => ({ value: p.key, label: `${p.key} · ${p.summary}` }))} value={form.parent} onChange={set('parent')} />
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap', fontSize: 12, color: 'var(--text-muted)' }}>
            <span>Phase:</span>
            <span style={{ font: '600 11px/20px var(--font-text)', padding: '0 8px', borderRadius: 999, background: 'var(--surface-tint)', color: 'var(--text-heading)' }}>{preview.data?.phase ?? '…'}</span>
            <span>· Prefix: <b className="mono heading">{preview.data?.prefix ?? '…'}</b></span>
            {preview.data?.function_id && <span>· Auto-generated function ID: <b className="mono heading">{preview.data.function_id}</b></span>}
          </div>
        </div>
        <div>
          <TextField label="Function name" placeholder="e.g. Installment payment" value={form.name} onChange={(v) => { setNameErr(undefined); set('name')(v); }}
            aria-invalid={!!nameErr} />
          {nameErr && <div role="alert" style={{ fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)', marginTop: 4 }}>{nameErr}</div>}
        </div>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(150px,1fr))', gap: 12 }}>
        <Select label="BA" options={baOpts} value={ba} onChange={set('ba')} />
        <Select label="Reviewer BAL" options={balOpts} value={bal} onChange={set('bal')} />
        {five && <Select label="Reviewer JM" options={jmOpts} value={jm} onChange={set('jm')} />}
        <div>
          <TextField label="Effort Create (hours)" type="number" min={0.5} step={0.5} value={form.hours} onChange={set('hours')} aria-invalid={!(hours > 0)} />
          {!(hours > 0) && <div role="alert" style={{ fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)', marginTop: 4 }}>Enter more than 0 hours</div>}
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', padding: '10px 12px', borderRadius: 12, background: 'var(--surface-hover)', fontSize: 12 }}>
        <Icon name="scale" size={14} />
        <span className="muted">Effort for remaining steps = Create × project weight</span>
        <span className="spacer" />
        {STEPS.slice(1, steps).map((s, i) => (
          <span key={s.k} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, height: 24, padding: '0 10px', borderRadius: 999, border: '1px solid var(--border-subtle)', background: 'var(--surface-card)' }}>
            <span className="muted">{s.label}</span>
            <span className="mono muted">×{WEIGHTS[i + 1]}</span>
            <b className="mono heading">{preview.data?.steps[i + 1] ? f1(preview.data.steps[i + 1].estimate_h) + 'h' : '–'}</b>
          </span>
        ))}
      </div>
      <div>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 6, flexWrap: 'wrap' }}>
          <span style={{ fontWeight: 600, color: 'var(--text-heading)' }}>Sub-tasks and plan per step</span>
          <span className="eyebrow">Suggested dates skip weekends, VN/JP holidays and leave; adjust any step if needed</span>
        </div>
        {preview.isError && <QueryError error={preview.error} />}
        <div style={{ border: '1px solid var(--border-subtle)', borderRadius: 14, overflowX: 'auto', opacity: preview.isFetching ? 0.7 : 1 }}>
          <div style={{ display: 'grid', gridTemplateColumns: COLS, gap: 10, alignItems: 'center', padding: '8px 12px', minWidth: 720, borderBottom: '1px solid var(--border-subtle)', background: 'var(--surface-hover)', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)' }}>
            <span>Summary</span><span>Assignee</span><span>Effort</span><span>Planned Start</span><span>Planned End</span>
          </div>
          {(preview.data?.steps ?? []).map((s, i) => {
            const a = av(members[s.assignee_id], s.assignee_id);
            const reasons = s.reasons.map(({ date, reason }) => `${dm(date)} ${reason}`).join(', ');
            const note = s.note === 'dayoff' ? 'Falls on a day off: ' + reasons : s.note === 'edited' ? 'Edited manually'
              : s.note === 'skips' ? 'Suggested · skips ' + reasons : 'Auto-suggested';
            const fg = s.note === 'dayoff' ? 'var(--hl-red-fg)' : s.note === 'skips' ? 'var(--hl-amber-fg)' : 'var(--text-muted)';
            return (
              <div key={s.step} style={{ display: 'grid', gridTemplateColumns: COLS, gap: 10, alignItems: 'center', padding: '6px 12px', minWidth: 720, borderBottom: '1px solid var(--border-subtle)', fontSize: 12 }}>
                <div style={{ minWidth: 0 }}>
                  <div className="ellipsis heading">{s.summary}</div>
                  <div style={{ fontSize: 11, color: fg }}>{note}</div>
                </div>
                <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}><Avatar a={a} size={18} />{a.name}</span>
                <span className="mono heading">{f1(s.estimate_h)}h</span>
                <TextField type="date" value={s.start} onChange={(v) => setDate(i, 'start', v)} aria-label={`${STEPS[i].label} planned start`} />
                <TextField type="date" value={s.end} onChange={(v) => setDate(i, 'end', v)} aria-label={`${STEPS[i].label} planned end`} />
              </div>
            );
          })}
          {!preview.data && <div className="empty-row">{ready ? 'Calculating…' : 'Choose members and the Create effort to see the plan.'}</div>}
        </div>
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 8 }}>
        {off && <span className="eyebrow">{off}</span>}
        <Button appearance="subtle" onClick={() => navigate({ to: '/anken/$epic/matrix', params: { epic } })}>Cancel</Button>
        <Button disabled={disabled} title={off} onClick={submit}>Create 5 sub-tasks in Jira</Button>
      </div>
    </div>
  );
}
