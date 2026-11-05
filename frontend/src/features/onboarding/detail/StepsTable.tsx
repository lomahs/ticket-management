import { useState, type CSSProperties } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { Button, IconButton } from '../../../shared/ds';
import { f1 } from '../../../shared/format/dates';
import { STEPS } from '../../../shared/format/labels';
import { useToast } from '../../../shared/state/toast';
import { useEditStep, type DetailPlanView, type StepBody } from './api';
import type { Person } from './DetailPlan';

type Row = Schemas['EstimationRow'];
type Step = Schemas['StepRow'];
type Slot = 'ba_id' | 'bal_id' | 'jm_id';

const COLS = '84px minmax(240px,3fr) 76px repeat(3,minmax(130px,1fr)) 138px 138px 64px';
const CELL: CSSProperties = { minWidth: 0 };
const INPUT: CSSProperties = { height: 30, boxSizing: 'border-box', padding: '0 6px', border: '1px solid var(--border-strong)', borderRadius: 6, background: 'var(--surface-card)', color: 'var(--text-heading)', font: '500 12px/1 var(--font-text)', width: '100%' };
const stepLabel = (k?: string | null) => STEPS.find((s) => s.k === k)?.label ?? 'Ticket';

/**
 * The plan's sub-tasks, one line per step (one per ticket), under their function. BA and BAL are the function's, each with the
 * hours they carry on the line (Settings › Step weights); Effort, the dates and the removal are per line.
 */
export function StepsTable({ epic, v, people, admin, assign, move }: {
  epic: string; v: DetailPlanView; people: Person[]; admin: boolean;
  assign: (r: Row, slot: Slot, id: string | null) => void; move: (i: number, d: -1 | 1) => void;
}) {
  const toast = useToast();
  const edit = useEditStep(epic);
  const [showRemoved, setShowRemoved] = useState(false);
  const open = v.rows.filter((r) => !r.pushed_at);
  const task = (key?: string | null) => v.tasks.find((t) => t.key === key);
  const send = (s: Step, p: Partial<StepBody>) => edit.mutate({
    row_id: s.row_id, step: s.step ?? null, effort_h: s.edited ? s.effort_h : null, start: s.manual ? s.start ?? null : null,
    end: s.manual ? s.end ?? null : null, removed: s.removed, ...p,
  }, { onError: (e) => toast(errorMessage(e), true) });
  const removed = v.steps.filter((s) => s.removed);
  // BA and BAL: anyone but a JM; JM: anyone but a BA or a BAL; whoever is picked stays listed
  const choices = (slot: Slot, picked?: string | null) => people.filter((m) => m.id === picked
    || (slot === 'jm_id' ? m.role !== 'BA' && m.role !== 'BAL' : m.role !== 'JM'));
  const person = (r: Row, s: Step, slot: Slot, hours: number | null) => {
    const label = `${slot === 'ba_id' ? (r.steps ? 'BA' : 'Member') : slot === 'bal_id' ? 'BAL' : 'JM'}, ${r.function_name} ${stepLabel(s.step)}`;
    return (
      <span style={{ ...CELL, display: 'flex', flexDirection: 'column', gap: 2 }}>
        <select aria-label={label} disabled={!admin} value={r[slot] ?? ''} onChange={(e) => assign(r, slot, e.target.value || null)} style={INPUT}>
          <option value="">–</option>
          {choices(slot, r[slot]).map((m) => <option key={m.id} value={m.id}>{m.role ? `${m.name} (${m.role})` : m.name}</option>)}
        </select>
        {hours != null && <span className="eyebrow">{hours > 0 ? `${f1(hours)}h` : s.step === 'jmr' ? 'not counted' : '0h'}</span>}
      </span>
    );
  };
  const date = (s: Step, which: 'start' | 'end') => (
    <input type="date" aria-label={`Plan ${which}, ${s.summary}`} disabled={!admin || !s.start} value={s[which] ?? ''}
      onChange={(e) => {
        const d = e.target.value;
        if (!d || !s.start || !s.end) return;
        const start = which === 'start' ? d : s.start;
        const end = which === 'end' ? d : s.end < d && which === 'start' ? d : s.end;
        send(s, { start, end: end < start ? start : end });
      }} style={{ ...INPUT, borderColor: s.manual ? 'var(--surface-accent)' : 'var(--border-strong)' }} />
  );
  return (
    <section className="card card-clip" aria-label="Detail plan sub-tasks">
      <div style={{ overflowX: 'auto' }}>
        <div style={{ minWidth: 1140 }}>
          <div role="row" className="eyebrow" style={{ display: 'grid', gridTemplateColumns: COLS, gap: 8, padding: '8px 16px', borderBottom: '1px solid var(--border-subtle)' }}>
            <span>Phase</span><span>Task (summary)</span><span>Effort</span><span>BA</span><span>BAL</span><span>JM</span><span>Plan start</span><span>Plan end</span><span />
          </div>
          {open.length === 0 && <div style={{ padding: 16, color: 'var(--text-muted)' }}>Every estimation row is in Jira.</div>}
          {open.map((r, i) => {
            const lines = v.steps.filter((s) => s.row_id === r.id && !s.removed);
            return (
              <div key={r.id} aria-label={r.function_name} role="group" style={{ borderTop: i ? '1px solid var(--border-subtle)' : undefined }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 16px', background: 'var(--surface-hover)' }}>
                  <IconButton icon="chevron-up" label={`Move ${r.function_name} up`} size="sm" appearance="subtle" disabled={!admin || i === 0} onClick={() => move(i, -1)} />
                  <IconButton icon="chevron-down" label={`Move ${r.function_name} down`} size="sm" appearance="subtle" disabled={!admin || i === open.length - 1} onClick={() => move(i, 1)} />
                  <span className="heading" style={{ fontWeight: 600 }}>{r.function_name}</span>
                  <span className="eyebrow ellipsis" title={task(r.task_key)?.summary}>
                    {`${r.steps ? `${r.steps} steps · Create ${f1(r.effort_h)}h` : `ticket · ${f1(r.effort_h)}h`} · ${r.task_key ? `${r.task_key} ${task(r.task_key)?.summary ?? ''}` : 'No Task'}`}
                  </span>
                </div>
                {lines.map((s) => (
                  <div key={s.ref} role="row" aria-label={s.summary} style={{ display: 'grid', gridTemplateColumns: COLS, gap: 8, alignItems: 'center', padding: '6px 16px' }}>
                    <span className="ellipsis" style={CELL}>{s.phase}</span>
                    <span className="ellipsis heading" style={CELL} title={s.summary}>{s.summary}</span>
                    <input type="number" min={0} step={0.5} aria-label={`Effort, ${s.summary}`} disabled={!admin} defaultValue={s.effort_h}
                      key={`${s.ref}:${s.effort_h}`} onBlur={(e) => {
                        const h = Number(e.target.value);
                        if (e.target.value.trim() !== '' && h >= 0 && h !== s.effort_h) send(s, { effort_h: h });
                      }} style={{ ...INPUT, borderColor: s.edited ? 'var(--surface-accent)' : 'var(--border-strong)' }} />
                    {person(r, s, 'ba_id', s.ba_h)}
                    {r.steps ? person(r, s, 'bal_id', s.bal_h) : <span className="muted">–</span>}
                    {r.steps ? person(r, s, 'jm_id', s.step === 'jmr' ? 0 : null) : <span className="muted">–</span>}
                    {date(s, 'start')}
                    {date(s, 'end')}
                    <span style={{ display: 'flex' }}>
                      {(s.edited || s.manual) && <IconButton icon="undo-2" label={`Reset ${s.summary}`} size="sm" appearance="subtle" disabled={!admin}
                        onClick={() => send(s, { effort_h: null, start: null, end: null })} />}
                      <IconButton icon="x" label={`Remove ${s.summary}`} size="sm" appearance="subtle" disabled={!admin} onClick={() => send(s, { removed: true })} />
                    </span>
                  </div>
                ))}
              </div>
            );
          })}
        </div>
      </div>
      {removed.length > 0 && (
        <div style={{ borderTop: '1px solid var(--border-subtle)', padding: '8px 16px' }}>
          <Button appearance="subtle" size="sm" iconBefore={showRemoved ? 'chevron-down' : 'chevron-right'} onClick={() => setShowRemoved((x) => !x)}>
            {`Removed (${removed.length})`}
          </Button>
          {showRemoved && removed.map((s) => (
            <div key={s.ref} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 0', fontSize: 13 }}>
              <span className="muted ellipsis" style={{ flex: 1 }}>{s.summary}</span>
              <span className="muted">{`${f1(s.effort_h)}h`}</span>
              <Button appearance="subtle" size="sm" disabled={!admin} onClick={() => send(s, { removed: false })}>Restore</Button>
            </div>
          ))}
        </div>
      )}
      {v.plan.length > 0 && <div className="eyebrow" style={{ padding: '8px 16px', borderTop: '1px solid var(--border-subtle)' }}>
        Scheduled in this order from each phase&apos;s Start; each member&apos;s day is filled up to their allocation. Blue fields were set by hand
        and stay when the plan is allocated again; <b>↶</b> computes them again.
      </div>}
    </section>
  );
}
