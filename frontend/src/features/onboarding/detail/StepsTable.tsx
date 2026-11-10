import { useState, type CSSProperties } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { Button, DateField, Icon, IconButton } from '../../../shared/ds';
import { dm, f1 } from '../../../shared/format/dates';
import { STEPS } from '../../../shared/format/labels';
import { useToast } from '../../../shared/state/toast';
import { useEditStep, type DetailPlanView, type StepBody } from './api';
import type { Person } from './DetailPlan';
import { checkText, checkTone } from './PlanSummary';

type Row = Schemas['EstimationRow'];
type Step = Schemas['StepRow'];
export type Slot = 'ba_id' | 'bal_id' | 'jm_id';

const COLS = 'minmax(260px,3fr) 80px minmax(150px,1.4fr) 140px 140px 64px';
const INPUT: CSSProperties = { height: 30, boxSizing: 'border-box', padding: '0 6px', border: '1px solid var(--border-strong)', borderRadius: 6, background: 'var(--surface-card)', color: 'var(--text-heading)', font: '500 12px/1 var(--font-text)', width: '100%' };
const step = (k?: string | null) => STEPS.find((s) => s.k === k);
/** A line Check found affected (amber) or whose dates were set by hand. */
const line = (manual: boolean, affected: boolean): CSSProperties => (affected
  ? { background: 'var(--hl-amber)', boxShadow: 'inset 3px 0 0 var(--hl-amber-fg)' }
  : manual ? { background: 'var(--hl-manual)', boxShadow: 'inset 3px 0 0 var(--hl-manual-fg)' } : {});
/** A value set by hand: kept when the plan is computed or allocated again. */
const byHand = (set: boolean): CSSProperties => (set
  ? { background: 'var(--hl-manual)', borderColor: 'var(--hl-manual-fg)', color: 'var(--hl-manual-fg)', fontWeight: 700 }
  : {});

/**
 * The plan's sub-tasks by phase, then by function in the order they are scheduled. A function's BA, BAL and JM are picked
 * once, on the function; each of its lines (one per step, one per ticket) shows who carries its hours (Settings › Step
 * weights), its effort and its dates, which can be set by hand.
 */
export function StepsTable({ epic, v, people, admin, assign, swap, affected = [] }: {
  epic: string; v: DetailPlanView; people: Person[]; admin: boolean;
  assign: (r: Row, slot: Slot, id: string | null) => void; swap: (a: number, b: number) => void;
  /** the refs the last Check found on a day over an allocation or after their phase's master End */
  affected?: string[];
}) {
  const toast = useToast();
  const edit = useEditStep(epic);
  const [showRemoved, setShowRemoved] = useState(false);
  const [folded, setFolded] = useState<Set<number>>(new Set());
  const name = (id?: string | null) => (id ? people.find((p) => p.id === id)?.name ?? id : '');
  const open = v.rows.filter((r) => !r.pushed_at);
  const task = (key?: string | null) => v.tasks.find((t) => t.key === key);
  const send = (s: Step, p: Partial<StepBody>) => edit.mutate({
    row_id: s.row_id, step: s.step ?? null, effort_h: s.edited ? s.effort_h : null, start: s.manual ? s.start ?? null : null,
    end: s.manual ? s.end ?? null : null, removed: s.removed, ...p,
  }, { onError: (e) => toast(errorMessage(e), true) });
  const removed = v.steps.filter((s) => s.removed && open.some((r) => r.id === s.row_id));
  // phases in the master schedule's order, then any other
  const phases = [...new Set([...v.checks.map((c) => c.phase), ...open.map((r) => r.phase)])].filter((p) => open.some((r) => r.phase === p));
  const fold = (id: number) => setFolded((f) => { const n = new Set(f); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const allFolded = open.length > 0 && open.every((r) => folded.has(r.id));

  // BA and BAL: anyone but a JM; JM: anyone but a BA or a BAL; whoever is picked stays listed
  const choices = (slot: Slot, picked?: string | null) => people.filter((m) => m.id === picked
    || (slot === 'jm_id' ? m.role !== 'BA' && m.role !== 'BAL' : m.role !== 'JM'));
  // a person picked by hand, not by Auto allocate: "Clear, keep my input" keeps them
  const picked = (r: Row, slot: Slot) => {
    const h = v.by_hand.find((x) => x.row_id === r.id);
    return !!h && (slot === 'ba_id' ? h.ba : slot === 'bal_id' ? h.bal : h.jm);
  };
  const person = (r: Row, slot: Slot, lines: Step[]) => {
    const role = slot === 'ba_id' ? (r.steps ? 'BA' : 'Member') : slot === 'bal_id' ? 'BAL' : 'JM';
    const hours = slot === 'ba_id' ? lines.reduce((a, s) => a + s.ba_h, 0) : slot === 'bal_id' ? lines.reduce((a, s) => a + s.bal_h, 0) : null;
    const hand = picked(r, slot);
    return (
      <label style={{ display: 'flex', flexDirection: 'column', gap: 3, width: 150 }}>
        <span className="eyebrow" style={{ fontSize: 11 }}>{hours == null ? `${role} · reviews` : `${role} · ${f1(hours)}h`}</span>
        <select aria-label={`${role}, ${r.function_name}`} disabled={!admin} value={r[slot] ?? ''} onChange={(e) => assign(r, slot, e.target.value || null)}
          title={hand ? 'Picked by hand: Clear, keep my input keeps it' : undefined} data-by-hand={hand || undefined}
          style={{ ...INPUT, ...(r[slot] ? {} : { borderColor: 'var(--hl-amber-fg)' }), ...byHand(hand) }}>
          <option value="">–</option>
          {choices(slot, r[slot]).map((m) => <option key={m.id} value={m.id}>{m.role ? `${m.name} (${m.role})` : m.name}</option>)}
        </select>
      </label>
    );
  };
  /** Who carries the line's hours: the BA and the BAL by the step weights; a JM review counts for nobody. */
  const carries = (r: Row, s: Step) => {
    if (s.step === 'jmr') return `${name(r.jm_id) || 'JM'} · not counted`;
    const parts = [s.ba_h > 0 && `${name(r.ba_id) || (r.steps ? 'BA' : 'Member')} ${f1(s.ba_h)}h`, s.bal_h > 0 && `${name(r.bal_id) || 'BAL'} ${f1(s.bal_h)}h`].filter(Boolean);
    return parts.length ? parts.join(' · ') : '0h';
  };
  // A typed date is sent on blur or Enter, not while it is typed; a date picked on the calendar at once. A cleared field keeps the date.
  // Dates can be set before the plan is computed (nobody picked yet): the first one set stands for both.
  const date = (s: Step, which: 'start' | 'end') => (
    <DateField bare sendOnLeave aria-label={`Plan ${which}, ${s.summary}`} disabled={!admin || s.removed} value={s[which] ?? ''}
      min="2000-01-01" max="2099-12-31"
      onChange={(d) => {
        if (!d || d === (s[which] ?? '')) return;
        const start = which === 'start' ? d : s.start ?? d;
        const end = which === 'end' ? d : !s.end || s.end < d ? d : s.end;
        send(s, { start, end: end < start ? start : end });
      }} style={{ ...INPUT, ...byHand(s.manual) }} />
  );

  const fn = (r: Row, k: number, rows: Row[]) => {
    const lines = v.steps.filter((s) => s.row_id === r.id && !s.removed);
    const starts = lines.map((s) => s.start).filter((x): x is string => !!x).sort();
    const ends = lines.map((s) => s.end).filter((x): x is string => !!x).sort();
    const closed = folded.has(r.id);
    const t = task(r.task_key);
    return (
      <div key={r.id} role="group" aria-label={r.function_name} style={{ borderTop: '1px solid var(--border-subtle)' }}>
        <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'flex-end', gap: 12, padding: '10px 16px', background: 'var(--surface-hover)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, flex: '1 1 280px', minWidth: 0, alignSelf: 'center' }}>
            <IconButton icon={closed ? 'chevron-right' : 'chevron-down'} label={`${closed ? 'Show' : 'Hide'} the lines of ${r.function_name}`} size="sm"
              aria-expanded={!closed} onClick={() => fold(r.id)} />
            <span style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
              <span className="heading" style={{ fontWeight: 700, fontSize: 14 }}>{r.function_name}</span>
              <span className="eyebrow ellipsis" title={t?.summary}>
                {`${r.steps ? `${r.steps} steps · Create ${f1(r.effort_h)}h` : `ticket · ${f1(r.effort_h)}h`} · ${r.task_key ? `${r.task_key} ${t?.name ?? ''}` : 'No Task'}`}
                {starts.length > 0 && ` · ${dm(starts[0])} → ${dm(ends[ends.length - 1])}`}
              </span>
            </span>
          </div>
          {person(r, 'ba_id', lines)}
          {r.steps ? person(r, 'bal_id', lines) : null}
          {/* a JM reviews 5-step functions only (JM Review); one picked already stays editable */}
          {r.steps && (r.steps > 3 || r.jm_id) ? person(r, 'jm_id', lines) : null}
          <span style={{ display: 'flex', alignSelf: 'center' }}>
            <IconButton icon="arrow-up" label={`Move ${r.function_name} up`} size="sm" disabled={!admin || k === 0} onClick={() => swap(r.id, rows[k - 1].id)} />
            <IconButton icon="arrow-down" label={`Move ${r.function_name} down`} size="sm" disabled={!admin || k === rows.length - 1} onClick={() => swap(r.id, rows[k + 1].id)} />
          </span>
        </div>
        {!closed && lines.map((s) => {
          const st = step(s.step);
          return (
            <div key={s.ref} role="row" aria-label={s.summary} data-affected={affected.includes(s.ref) || undefined}
              title={affected.includes(s.ref) ? 'Check: on a day over the allocation, or after the phase\'s master End' : undefined}
              style={{ display: 'grid', gridTemplateColumns: COLS, gap: 8, alignItems: 'center', padding: '5px 16px 5px 52px', ...line(s.manual, affected.includes(s.ref)) }}>
              <span style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }} title={s.summary}>
                <span aria-hidden="true" style={{ width: 8, height: 8, flex: 'none', borderRadius: 2, background: st?.color ?? 'var(--border-strong)' }} />
                <span style={{ width: 104, flex: 'none', fontWeight: 600, fontSize: 12 }} className="heading">{st?.label ?? 'Ticket'}</span>
                {s.manual && <span role="img" aria-label="Dates set by hand" title="Dates set by hand: Auto allocate keeps them and plans the rest around them"
                  style={{ display: 'flex', color: 'var(--hl-manual-fg)' }}><Icon name="lock" size={13} /></span>}
                <span className="ellipsis muted" style={{ fontSize: 12 }}>{s.summary}</span>
              </span>
              <input type="number" min={0} step={0.5} aria-label={`Effort, ${s.summary}`} disabled={!admin} defaultValue={s.effort_h}
                key={`${s.ref}:${s.effort_h}`} onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); }} onBlur={(e) => {
                  const h = Number(e.target.value);
                  if (e.target.value.trim() !== '' && h >= 0 && h !== s.effort_h) send(s, { effort_h: h });
                }} style={{ ...INPUT, ...byHand(s.edited) }} />
              <span className="ellipsis" style={{ fontSize: 12 }}>{carries(r, s)}</span>
              {date(s, 'start')}
              {date(s, 'end')}
              <span style={{ display: 'flex' }}>
                {(s.edited || s.manual) && <IconButton icon="undo-2" label={`Reset ${s.summary}`} size="sm" disabled={!admin}
                  title="Compute its effort and dates again" onClick={() => send(s, { effort_h: null, start: null, end: null })} />}
                <IconButton icon="x" label={`Remove ${s.summary}`} size="sm" disabled={!admin} onClick={() => send(s, { removed: true })} />
              </span>
            </div>
          );
        })}
      </div>
    );
  };

  return (
    <section className="card card-clip" aria-label="Detail plan sub-tasks">
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 12, padding: '8px 16px', borderBottom: '1px solid var(--border-subtle)' }}>
        <span className="legend">
          <span><span className="swatch" style={{ background: 'var(--hl-manual)', boxShadow: 'inset 0 0 0 1px var(--hl-manual-fg)' }} />Set by hand: kept when the plan is computed again, Auto allocate included</span>
          <span><Icon name="undo-2" size={12} />computes it again</span>
        </span>
        <span className="spacer" />
        {open.length > 0 && <Button appearance="subtle" size="sm" onClick={() => setFolded(allFolded ? new Set() : new Set(open.map((r) => r.id)))}>
          {allFolded ? 'Show every line' : 'Functions only'}</Button>}
      </div>
      <div style={{ overflowX: 'auto' }}>
        <div style={{ minWidth: 1000 }}>
          <div role="row" className="eyebrow" style={{ display: 'grid', gridTemplateColumns: COLS, gap: 8, padding: '8px 16px 8px 52px' }}>
            <span>Step · sub-task</span><span>Effort (h)</span><span>Carried by</span><span>Plan start</span><span>Plan end</span><span />
          </div>
          {open.length === 0 && <div className="empty-row" style={{ fontSize: 13 }}>Every estimation row is in Jira.</div>}
          {phases.map((p) => {
            const rows = open.filter((r) => r.phase === p);
            const c = v.checks.find((x) => x.phase === p);
            return (
              <div key={p} role="group" aria-label={`Phase ${p}`}>
                <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 10, padding: '12px 16px 8px', borderTop: '1px solid var(--border-strong)' }}>
                  <span className="title" style={{ fontSize: 15 }}>{p}</span>
                  <span className="eyebrow">{`${rows.length} function${rows.length === 1 ? '' : 's'}`}</span>
                  {c && <span className="status-pill" aria-hidden="true" style={checkTone(c)}>{checkText(c).replace(`${p} `, '').replace(`${p}: `, '')}</span>}
                </div>
                {rows.map((r, k) => fn(r, k, rows))}
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
    </section>
  );
}
