import { useState, type CSSProperties, type ReactNode } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useMeta } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button, IconButton } from '../../../shared/ds';
import { dm, f2, WDN } from '../../../shared/format/dates';
import { STEPS } from '../../../shared/format/labels';
import { useToast } from '../../../shared/state/toast';
import { useEditJiraLine, useEditStep, useSaveCells, type DetailPlanView, type MatrixLine } from './api';
import type { Person } from './DetailPlan';
import { checkText, checkTone } from './PlanSummary';

type Row = Schemas['EstimationRow'];
export type Slot = 'ba_id' | 'bal_id' | 'jm_id';

const INPUT: CSSProperties = { height: 28, boxSizing: 'border-box', padding: '0 6px', border: '1px solid var(--border-strong)', borderRadius: 6, background: 'var(--surface-card)', color: 'var(--text-heading)', font: '500 12px/1 var(--font-text)', width: '100%' };
const CELL: CSSProperties = { padding: '3px 4px', borderBottom: '1px solid var(--border-subtle)', fontSize: 12, whiteSpace: 'nowrap', textAlign: 'right' };
const DAY_W = 52;
const STICKY: CSSProperties = { position: 'sticky', left: 0, zIndex: 1, background: 'var(--surface-card)' };
const RED: CSSProperties = { background: 'var(--hl-red)', color: 'var(--hl-red-fg)' };
/** A value set by hand: kept when Auto allocate runs again. */
const byHand = (set: boolean): CSSProperties => (set
  ? { background: 'var(--hl-manual)', borderColor: 'var(--hl-manual-fg)', color: 'var(--hl-manual-fg)', fontWeight: 700 }
  : {});
const step = (k?: string | null) => STEPS.find((s) => s.k === k);
/** Hours as they are typed: "8", "1.5"; nothing for none. */
const h = (n: number) => (Math.abs(n) < 0.005 ? '' : String(Number(f2(n))));
const sum = (cells: MatrixLine['cells'], day: string) => cells.filter((c) => c.day === day).reduce((a, c) => a + c.hours, 0);
const weekday = (d: string) => WDN[new Date(`${d}T00:00:00Z`).getUTCDay()];

/**
 * Hours typed in a box: sent once the box is left (or Enter), only when they changed; what was typed stays on screen until the
 * plan comes back. A cleared box is 0 when {@code blank} is 0, else it is put back.
 */
function HoursInput({ label, value, blank, send, style }: {
  label: string; value: number; blank: 'zero' | 'keep'; send: (hours: number) => Promise<unknown>; style?: CSSProperties;
}) {
  const [draft, setDraft] = useState<string | null>(null);
  const shown = draft ?? (blank === 'zero' ? h(value) : String(Number(f2(value))));
  const leave = () => {
    if (draft == null) return;
    const t = draft.trim();
    const n = t === '' && blank === 'zero' ? 0 : Number(t);
    if (t === '' && blank === 'keep') { setDraft(null); return; }
    if (!Number.isFinite(n) || n < 0 || Math.abs(n - value) < 0.005) { setDraft(null); return; }
    void send(Math.round(n * 100) / 100).finally(() => setDraft(null));
  };
  return (
    <input type="number" min={0} step={0.5} aria-label={label} value={shown} onChange={(e) => setDraft(e.target.value)}
      onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); }} onBlur={leave}
      style={{ ...INPUT, textAlign: 'right', padding: '0 4px', ...style }} />
  );
}

/** A function of the plan: a row not in Jira yet with its steps, or a function of the sub-tasks already in Jira. */
interface Group { key: string; name: string; row?: Row; lines: MatrixLine[] }

/**
 * The plan as a matrix: one line per step of a function (one per ticket), grouped by phase then function, with its PIC, Total,
 * Planned, Not yet and dates, then the hours planned on each day of the plan. The rows not in Jira yet pick their BA, BAL and JM
 * on the function; the sub-tasks in Jira change their PIC and Total here, kept until the push. Days before today of a sub-task in
 * Jira are locked and show the hours logged under the plan.
 */
export function PlanMatrix({ epic, v, people, admin, assign, swap }: {
  epic: string; v: DetailPlanView; people: Person[]; admin: boolean;
  assign: (r: Row, slot: Slot, id: string | null) => void; swap: (a: number, b: number) => void;
}) {
  const toast = useToast();
  const today = useMeta().data?.today ?? '';
  const edit = useEditStep(epic);
  const cells = useSaveCells(epic);
  const jira = useEditJiraLine(epic);
  const [folded, setFolded] = useState<Set<string>>(new Set());
  const name = (id?: string | null) => (id ? people.find((p) => p.id === id)?.name ?? id : '');
  const open = v.rows.filter((r) => !r.pushed_at);
  const task = (key?: string | null) => v.tasks.find((t) => t.key === key);
  const fail = (e: unknown) => toast(errorMessage(e), true);
  const sendStep = (l: MatrixLine, p: { effort_h?: number | null; removed?: boolean }) => {
    if (l.row_id == null) return Promise.resolve();
    return edit.mutateAsync({ row_id: l.row_id, step: l.step, effort_h: l.edited ? l.total_h : null, removed: l.removed, ...p }).catch(fail);
  };
  const sendCell = (l: MatrixLine, day: string, hours: number) => cells.mutateAsync([{ ref: l.ref, day, hours }]).catch(fail);
  const sendJira = (key: string, total: number | null, pic: string | null) => jira.mutateAsync({ key, total_h: total, pic_id: pic }).catch(fail);

  // phases in the master schedule's order, then any other; in each, the rows not in Jira yet, then the functions in Jira
  const phases = [...new Set([...v.checks.map((c) => c.phase), ...open.map((r) => r.phase), ...v.lines.map((l) => l.phase)])]
    .filter((p) => open.some((r) => r.phase === p) || v.lines.some((l) => l.phase === p));
  const groups = (p: string): Group[] => {
    const rows = open.filter((r) => r.phase === p).map((r) => ({ key: `row:${r.id}`, name: r.function_name, row: r, lines: v.lines.filter((l) => l.row_id === r.id) }));
    const inJira = new Map<string, Group>();
    for (const l of v.lines) {
      if (l.issue_key == null || l.phase !== p) continue;
      const g = inJira.get(l.function) ?? { key: `jira:${p}:${l.function}`, name: l.function, lines: [] };
      g.lines.push(l);
      inJira.set(l.function, g);
    }
    return [...rows, ...inJira.values()];
  };
  const all = phases.flatMap((p) => groups(p).map((g) => g.key));
  const allFolded = all.length > 0 && all.every((k) => folded.has(k));
  const fold = (k: string) => setFolded((f) => { const n = new Set(f); if (n.has(k)) n.delete(k); else n.add(k); return n; });
  const cols = 7 + v.window.length;

  // BA and BAL: anyone but a JM; JM: anyone but a BA or a BAL; whoever is picked stays listed
  const choices = (slot: Slot, picked?: string | null) => people.filter((m) => m.id === picked
    || (slot === 'jm_id' ? m.role !== 'BA' && m.role !== 'BAL' : m.role !== 'JM'));
  // a person picked by hand, not by Auto allocate: "Clear, keep my input" keeps them
  const picked = (r: Row, slot: Slot) => {
    const x = v.by_hand.find((y) => y.row_id === r.id);
    return !!x && (slot === 'ba_id' ? x.ba : slot === 'bal_id' ? x.bal : x.jm);
  };
  const person = (r: Row, slot: Slot) => {
    const role = slot === 'ba_id' ? (r.steps ? 'BA' : 'Member') : slot === 'bal_id' ? 'BAL' : 'JM';
    const hand = picked(r, slot);
    return (
      <label key={slot} style={{ display: 'flex', flexDirection: 'column', gap: 3, width: 150 }}>
        <span className="eyebrow" style={{ fontSize: 11 }}>{slot === 'jm_id' ? `${role} · reviews` : role}</span>
        <select aria-label={`${role}, ${r.function_name}`} disabled={!admin} value={r[slot] ?? ''} onChange={(e) => assign(r, slot, e.target.value || null)}
          title={hand ? 'Picked by hand: Clear, keep my input keeps it' : undefined} data-by-hand={hand || undefined}
          style={{ ...INPUT, ...(r[slot] ? {} : { borderColor: 'var(--hl-amber-fg)' }), ...byHand(hand) }}>
          <option value="">–</option>
          {choices(slot, r[slot]).map((m) => <option key={m.id} value={m.id}>{m.role ? `${m.name} (${m.role})` : m.name}</option>)}
        </select>
      </label>
    );
  };

  const header = (g: Group, k: number, rows: Group[]) => {
    const r = g.row;
    const closed = folded.has(g.key);
    const t = task(r?.task_key);
    const prev = rows[k - 1]?.row;
    const next = rows[k + 1]?.row;
    let about: string;
    if (r) about = `${r.steps ? `${r.steps} steps · Create ${f2(r.effort_h)}h` : `ticket · ${f2(r.effort_h)}h`} · ${r.task_key ? `${r.task_key} ${t?.name ?? ''}` : 'No Task'}`;
    else about = `In Jira · ${g.lines.length} sub-task${g.lines.length === 1 ? '' : 's'}`;
    return (
      <tr key={g.key} aria-label={`Function ${g.name}`}>
        <td colSpan={cols} style={{ padding: 0, borderTop: '1px solid var(--border-subtle)', background: 'var(--surface-hover)' }}>
          <div style={{ ...STICKY, background: 'var(--surface-hover)', display: 'flex', flexWrap: 'wrap', alignItems: 'flex-end', gap: 12, padding: '8px 16px', width: 'max-content', maxWidth: '100vw', boxSizing: 'border-box' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 240, alignSelf: 'center' }}>
              <IconButton icon={closed ? 'chevron-right' : 'chevron-down'} label={`${closed ? 'Show' : 'Hide'} the lines of ${g.name}`} size="sm"
                aria-expanded={!closed} onClick={() => fold(g.key)} />
              <span style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
                <span className="heading" style={{ fontWeight: 700, fontSize: 14 }}>{g.name}</span>
                <span className="eyebrow ellipsis" title={t?.summary} style={{ maxWidth: 360 }}>{about}</span>
              </span>
            </div>
            {r && person(r, 'ba_id')}
            {r?.steps ? person(r, 'bal_id') : null}
            {/* a JM reviews 5-step functions only (JM Review); one picked already stays editable */}
            {r?.steps && (r.steps > 3 || r.jm_id) ? person(r, 'jm_id') : null}
            {r && (
              <span style={{ display: 'flex', alignSelf: 'center' }}>
                <IconButton icon="arrow-up" label={`Move ${r.function_name} up`} size="sm" disabled={!admin || !prev} onClick={() => { if (prev) swap(r.id, prev.id); }} />
                <IconButton icon="arrow-down" label={`Move ${r.function_name} down`} size="sm" disabled={!admin || !next} onClick={() => { if (next) swap(r.id, next.id); }} />
              </span>
            )}
          </div>
        </td>
      </tr>
    );
  };

  const line = (l: MatrixLine) => {
    const st = step(l.step);
    const inJira = l.issue_key != null;
    const key = l.issue_key ?? '';
    const live = admin && !l.removed && !l.closed; // its Total, PIC and days can change
    const red = l.out_of_order ? RED : {};
    let pic: ReactNode;
    if (inJira && live) {
      const now = l.pic_id;
      const options = !now || people.some((p) => p.id === now) ? people : [...people, { id: now, name: name(now) }];
      pic = (
        <select aria-label={`PIC, ${l.summary}`} value={l.pic_id ?? ''} onChange={(e) => { if (e.target.value) void sendJira(key, null, e.target.value); }}
          style={{ ...INPUT, width: 110 }}>
          {!l.pic_id && <option value="">–</option>}
          {options.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
      );
    } else {
      pic = <span aria-label={`PIC, ${l.summary}`}>{name(l.pic_id) || '–'}</span>;
    }
    let total: ReactNode;
    if (live && inJira) total = <HoursInput label={`Total, ${l.summary}`} value={l.total_h} blank="keep" send={(n) => sendJira(key, n, null)} />;
    else if (live) total = <HoursInput label={`Total, ${l.summary}`} value={l.total_h} blank="keep" send={(n) => sendStep(l, { effort_h: n })} style={byHand(l.edited)} />;
    else total = f2(l.total_h);
    return (
      <tr key={l.ref} aria-label={l.summary} data-out-of-order={l.out_of_order || undefined} data-removed={l.removed || undefined}
        title={l.out_of_order ? 'Starts before the step before it ends: push is blocked until it is fixed' : undefined}
        style={{ opacity: l.removed ? 0.5 : 1 }}>
        <th scope="row" style={{ ...CELL, ...STICKY, textAlign: 'left', fontWeight: 400, padding: '3px 8px 3px 44px', minWidth: 300, maxWidth: 380 }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
            <span aria-hidden="true" style={{ width: 8, height: 8, flex: 'none', borderRadius: 2, background: st?.color ?? 'var(--border-strong)' }} />
            {inJira && <span className="key" style={{ flex: 'none' }}>{l.issue_key}</span>}
            <span className="ellipsis heading" title={l.summary} style={{ fontSize: 12, textDecoration: l.removed ? 'line-through' : undefined }}>{l.summary}</span>
            {l.pending && <span className="status-pill" style={{ flex: 'none', background: 'var(--hl-amber)', color: 'var(--hl-amber-fg)' }}
              title="Changed here: sent to Jira with the push">Not pushed</span>}
            {l.closed && <span className="status-pill" style={{ flex: 'none' }}>Closed</span>}
            <span className="spacer" />
            {!inJira && admin && l.edited && !l.removed && <IconButton icon="undo-2" label={`Reset ${l.summary}`} size="sm"
              title="Back to the effort of the estimation" onClick={() => void sendStep(l, { effort_h: null })} />}
            {!inJira && admin && !l.removed && <IconButton icon="x" label={`Remove ${l.summary}`} size="sm" onClick={() => void sendStep(l, { removed: true })} />}
            {!inJira && l.removed && <Button appearance="subtle" size="sm" aria-label={`Restore ${l.summary}`} disabled={!admin}
              onClick={() => void sendStep(l, { removed: false })}>Restore</Button>}
          </span>
        </th>
        <td style={{ ...CELL, textAlign: 'left' }}>{pic}</td>
        <td style={{ ...CELL, width: 64 }}>{total}</td>
        <td style={CELL} aria-label={`Planned, ${l.summary}`}>{f2(l.planned_h)}</td>
        <td style={{ ...CELL, ...(Math.abs(l.not_yet_h) >= 0.005 && !l.removed ? { ...RED, fontWeight: 700 } : {}) }} aria-label={`Not yet, ${l.summary}`}>
          {f2(l.not_yet_h)}</td>
        <td style={CELL} aria-label={`Start, ${l.summary}`}>{l.start ? dm(l.start) : '–'}</td>
        <td style={CELL} aria-label={`End, ${l.summary}`}>{l.end ? dm(l.end) : '–'}</td>
        {v.window.map((d) => {
          const planned = sum(l.cells, d);
          const logged = sum(l.logged, d);
          // a sub-task in Jira cannot be planned before today: those days happened
          const locked = !live || (inJira && (!today || d < today));
          const typed = !inJira && l.cells.some((c) => c.day === d && !c.auto && c.hours > 0);
          return (
            <td key={d} data-day={d} data-out-of-order={(l.out_of_order && !l.removed) || undefined}
              style={{ ...CELL, width: DAY_W, minWidth: DAY_W, ...(d === today ? { background: 'var(--surface-tint)' } : {}), ...(l.removed ? {} : red) }}>
              {locked
                ? <span style={{ display: 'block', padding: '0 4px' }}>{h(planned)}</span>
                : <HoursInput label={`${l.summary} ${d}`} value={planned} blank="zero" send={(n) => sendCell(l, d, n)} style={byHand(typed)} />}
              {logged > 0 && <span className="muted" title={`${h(logged)}h logged`} style={{ display: 'block', fontSize: 10, padding: '0 4px' }}>{`${h(logged)}h`}</span>}
            </td>
          );
        })}
      </tr>
    );
  };

  return (
    <section className="card card-clip" aria-label="Detail plan matrix">
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 12, padding: '8px 16px', borderBottom: '1px solid var(--border-subtle)' }}>
        <span className="legend">
          <span><span className="swatch" style={{ background: 'var(--hl-manual)', boxShadow: 'inset 0 0 0 1px var(--hl-manual-fg)' }} />Typed by hand: Auto allocate keeps it</span>
          <span><span className="swatch" style={{ background: 'var(--hl-red)' }} />Out of step order, or Not yet left: blocks the push</span>
          <span>Days before today of a sub-task in Jira are locked; the hours logged show under the plan</span>
        </span>
        <span className="spacer" />
        {all.length > 0 && <Button appearance="subtle" size="sm" onClick={() => setFolded(allFolded ? new Set() : new Set(all))}>
          {allFolded ? 'Show every line' : 'Functions only'}</Button>}
      </div>
      <div style={{ overflowX: 'auto' }}>
        <table style={{ borderCollapse: 'separate', borderSpacing: 0, color: 'var(--text-body)', minWidth: '100%' }}>
          <thead>
            <tr className="eyebrow">
              <th scope="col" style={{ ...CELL, ...STICKY, textAlign: 'left', padding: '6px 8px 6px 44px', zIndex: 2 }}>Step · sub-task</th>
              <th scope="col" style={{ ...CELL, textAlign: 'left' }}>PIC</th>
              <th scope="col" style={CELL}>Total</th>
              <th scope="col" style={CELL}>Planned</th>
              <th scope="col" style={CELL}>Not yet</th>
              <th scope="col" style={CELL}>Start</th>
              <th scope="col" style={CELL}>End</th>
              {v.window.map((d) => (
                <th key={d} scope="col" aria-label={d} style={{ ...CELL, textAlign: 'center', ...(d === today ? { background: 'var(--surface-tint)', color: 'var(--text-heading)' } : {}) }}>
                  <span style={{ display: 'block', fontWeight: 500 }}>{weekday(d)}</span>{dm(d)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {phases.length === 0 && (
              <tr><td colSpan={cols} className="empty-row" style={{ fontSize: 13 }}>Nothing to plan yet: add a function or a task.</td></tr>
            )}
            {phases.flatMap((p) => {
              const gs = groups(p);
              const rows = gs.filter((g) => g.row);
              const c = v.checks.find((x) => x.phase === p);
              return [
                <tr key={`phase:${p}`} aria-label={`Phase ${p}`}>
                  <td colSpan={cols} style={{ padding: 0, borderTop: '1px solid var(--border-strong)' }}>
                    <div style={{ ...STICKY, display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 10, padding: '12px 16px 8px', width: 'max-content' }}>
                      <span className="title" style={{ fontSize: 15 }}>{p}</span>
                      <span className="eyebrow">{`${gs.length} function${gs.length === 1 ? '' : 's'}`}</span>
                      {c && <span className="status-pill" aria-hidden="true" style={checkTone(c)}>{checkText(c).replace(`${p} `, '').replace(`${p}: `, '')}</span>}
                    </div>
                  </td>
                </tr>,
                ...gs.flatMap((g) => [
                  header(g, rows.indexOf(g), rows),
                  ...(folded.has(g.key) ? [] : g.lines.map(line)),
                ]),
              ];
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}
