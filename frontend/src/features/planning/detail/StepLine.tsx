import { createContext, memo, use, useState, type ReactNode } from 'react';
import type { Schemas } from '../../../shared/api/types';
import { Button, Icon, IconButton } from '../../../shared/ds';
import { dm, f2 } from '../../../shared/format/dates';
import { STEPS } from '../../../shared/format/labels';
import type { MatrixLine } from './api';
import { usePersonName, type Person } from './DetailPlan';
import { hoursByDay, type LineMarks } from './matrixModel';
import { overDayText } from './PlanSummary';

type Row = Schemas['EstimationRow'];
export type Slot = 'ba_id' | 'bal_id' | 'jm_id';

/** Not yet within a hundredth counts as planned, as the push sees it. */
const NOT_YET_EPS = 0.01;
export const step = (k?: string | null) => STEPS.find((s) => s.k === k);
/** Hours as they are typed: "8", "1.5"; nothing for none. */
const h = (n: number) => (Math.abs(n) < 0.005 ? '' : String(Number(f2(n))));
const SLOT: Record<string, Slot> = { ba: 'ba_id', bal: 'bal_id', jm: 'jm_id' };
/** Whether a line still has hours to plan, as the push sees it. */
export const left = (l: MatrixLine) => Math.abs(l.plan_not_yet_h) > NOT_YET_EPS && !l.removed && !l.closed;
/** The slot of the function a step of a row not in Jira yet picks its person in; a sub-task in Jira has its own PIC. */
export const slotOf = (l: MatrixLine): Slot | null => (l.issue_key == null && l.pic_slot ? SLOT[l.pic_slot] ?? null : null);

/**
 * What a step line asks of the matrix. The matrix gives one object for its whole life, so a line renders again only when its
 * own data changes.
 */
export interface LineActions {
  sendCell: (l: MatrixLine, day: string, hours: number) => Promise<unknown>;
  sendStep: (l: MatrixLine, p: { effort_h?: number | null; removed?: boolean }) => Promise<unknown>;
  sendJira: (key: string, total: number | null, pic: string | null) => Promise<unknown>;
  deleteStep: (l: MatrixLine) => void;
  assign: (r: Row, slot: Slot, id: string | null) => void;
  togglePick: (key: string) => void;
  openTicket: (key: string) => void;
  /** Folds or unfolds a level of the matrix. */
  fold: (key: string) => void;
  /** Swaps two rows not in Jira yet in the order Auto allocate plans them. */
  swap: (a: number, b: number) => void;
}
const noop = () => Promise.resolve();
export const LineActionsContext = createContext<LineActions>({
  sendCell: noop, sendStep: noop, sendJira: noop, deleteStep: () => {}, assign: () => {}, togglePick: () => {}, openTicket: () => {}, fold: () => {},
  swap: () => {},
});

/**
 * Hours typed in a box: sent once the box is left (or Enter), only when they changed; what was typed stays on screen until the
 * plan comes back. A cleared box is 0 when {@code blank} is 0, else it is put back.
 */
function HoursInput({ label, value, blank, send, className, autoFocus, onDone }: {
  label: string; value: number; blank: 'zero' | 'keep'; send: (hours: number) => Promise<unknown>; className?: string;
  autoFocus?: boolean; onDone?: () => void;
}) {
  const [draft, setDraft] = useState<string | null>(null);
  const shown = draft ?? (blank === 'zero' ? h(value) : String(Number(f2(value))));
  const leave = () => {
    if (draft == null) return;
    const t = draft.trim();
    const n = t === '' && blank === 'zero' ? 0 : Number(t);
    if (t === '' && blank === 'keep') { setDraft(null); return; }
    if (!Number.isFinite(n) || n < 0 || Math.abs(n - value) < 0.005) { setDraft(null); return; }
    const sent = draft;
    // what was typed again meanwhile stays until it is left in turn
    void send(Math.round(n * 100) / 100).finally(() => setDraft((d) => (d === sent ? null : d)));
  };
  return (
    <input type="number" min={0} step={0.5} aria-label={label} value={shown} onChange={(e) => setDraft(e.target.value)} autoFocus={autoFocus}
      onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); }} onBlur={() => { leave(); onDone?.(); }}
      className={`pm-in${shown === '' ? ' empty' : ''}${className ? ` ${className}` : ''}`} />
  );
}

/**
 * A day cell of a line that can be planned: its hours as text, and a box only while it has the focus (a click or Tab), so the
 * matrix holds one box, not one per day. The box sends what is typed when it is left, as {@link HoursInput} does.
 */
function DayCell({ label, value, hand, send }: { label: string; value: number; hand: boolean; send: (hours: number) => Promise<unknown> }) {
  const [typing, setTyping] = useState(false);
  if (typing) {
    return <HoursInput label={label} value={value} blank="zero" send={send} className={hand ? 'hand' : undefined} autoFocus onDone={() => setTyping(false)} />;
  }
  const shown = h(value);
  return (
    <span role="button" tabIndex={0} aria-label={label} onFocus={() => setTyping(true)}
      className={`pm-in pm-in-v${shown === '' ? ' empty' : ''}${hand ? ' hand' : ''}`}>{shown}</span>
  );
}

/**
 * What is wrong on a row or a cell: an error (Invalid, red: it stops the push) or a warning (amber: it does not). Its messages
 * open above everything on hover or focus, not clipped by the scrolling matrix.
 */
export function Mark({ level, msgs }: { level: 'error' | 'warning'; msgs: string[] }) {
  const [at, setAt] = useState<DOMRect | null>(null);
  if (!msgs.length) return null;
  const show = (e: { currentTarget: Element }) => setAt(e.currentTarget.getBoundingClientRect());
  return (
    <span className={`pm-mark ${level}`} tabIndex={0} role="img" aria-label={`${level === 'error' ? 'Invalid' : 'Warning'}: ${msgs.join('; ')}`}
      onMouseEnter={show} onFocus={show} onMouseLeave={() => setAt(null)} onBlur={() => setAt(null)}>
      <Icon name={level === 'error' ? 'circle-alert' : 'triangle-alert'} size={12} />
      {at && (
        <span className={`pm-mark-tip ${level}`} role="tooltip" style={{ left: Math.max(8, at.left - 8), top: at.bottom + 6 }}>
          {msgs.map((m) => <span key={m}>{m}</span>)}
        </span>
      )}
    </span>
  );
}

const NO_MARKS: LineMarks = { pic: [], notYet: [], notYetJira: [], order: [], orderJira: [], overDay: [] };

/**
 * One step of the matrix (a sub-task): its PIC, Plan Estimation, Planned, Not yet plan, dates, then its hours on each day.
 *
 * @param row      the row not in Jira yet the step is of; none for a sub-task in Jira
 * @param hand     the slots of that row picked by hand
 * @param marks    what is wrong on the line ({@link LineMarks})
 * @param picked   picked to shift or push
 * @param deleting a removed step is being deleted for good
 * @param alone    the only line of a function in Jira without steps: it stands for the function, with no function row above it
 */
export const StepLine = memo(function StepLine({ l, row: r, hand, marks = NO_MARKS, days, today, admin, picked, people, deleting, alone = false }: {
  l: MatrixLine; row?: Row; hand?: Schemas['HandPicks']; marks?: LineMarks; days: string[]; today: string; admin: boolean;
  picked: boolean; people: Person[]; deleting: boolean; alone?: boolean;
}) {
  const act = use(LineActionsContext);
  const personName = usePersonName(people);
  const name = (id?: string | null) => (id ? personName(id) : '');
  const st = step(l.step);
  const inJira = l.issue_key != null;
  const key = l.issue_key ?? '';
  const live = admin && !l.removed && !l.closed; // its Plan Estimation, PIC and days can change
  const slot = slotOf(l);
  const plan = hoursByDay(l.cells);
  const logs = hoursByDay(l.logged);
  const typedDays = new Set(l.cells.filter((c) => !c.auto && c.hours > 0).map((c) => c.day));
  // BA and BAL: anyone but a JM; JM: anyone but a BA or a BAL; whoever is picked stays listed
  const choices = (s: Slot, now?: string | null) => people.filter((m) => m.id === now
    || (s === 'jm_id' ? m.role !== 'BA' && m.role !== 'BAL' : m.role !== 'JM'));
  let pic: ReactNode;
  if (!inJira && slot && r && admin && !l.removed) {
    // a person picked by hand, not by Auto allocate: "Clear, keep my input" keeps them
    const byHand = !!hand && (slot === 'ba_id' ? hand.ba : slot === 'bal_id' ? hand.bal : hand.jm);
    pic = (
      <select className="pm-pic" aria-label={`PIC, ${l.summary}`} value={r[slot] ?? ''} onChange={(e) => act.assign(r, slot, e.target.value || null)}
        data-by-hand={byHand || undefined} data-empty={!r[slot] || undefined}
        title={byHand ? 'Picked by hand: Clear, keep my input keeps it' : slot === 'jm_id' ? 'The JM who reviews the function' : undefined}>
        <option value="">–</option>
        {choices(slot, r[slot]).map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
      </select>
    );
  } else if (inJira && live) {
    const now = l.pic_id;
    const options = !now || people.some((p) => p.id === now) ? people : [...people, { id: now, name: name(now) }];
    pic = (
      <select className="pm-pic" aria-label={`PIC, ${l.summary}`} value={l.pic_id ?? ''} onChange={(e) => { if (e.target.value) void act.sendJira(key, null, e.target.value); }}>
        {!l.pic_id && <option value="">–</option>}
        {options.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
      </select>
    );
  } else {
    pic = <span aria-label={`PIC, ${l.summary}`}>{name(slot && r ? r[slot] : l.pic_id) || '–'}</span>;
  }
  // a sub-task's Plan Estimation is its Original Estimate in Jira: changed there, not here
  const estimate = live && !inJira
    ? <HoursInput label={`Plan Estimation, ${l.summary}`} value={l.total_h} blank="keep" send={(n) => act.sendStep(l, { effort_h: n })} className={`pm-in-est${l.edited ? ' hand' : ''}`} />
    : <span aria-label={`Plan Estimation, ${l.summary}`}>{f2(l.plan_estimate_h)}</span>;
  return (
    <tr className={`pm-step${alone ? ' pm-alone' : ''}`} aria-label={l.summary} data-ref={l.ref} data-out-of-order={l.out_of_order || undefined} data-removed={l.removed || undefined}
      title={l.out_of_order ? `Starts before the step before it ends${marks.order.length ? ': push is blocked until it is fixed' : ''}` : undefined}>
      <th scope="row" className="pm-c0">
        <span className="pm-stp">
          {admin && (!l.removed && !l.closed
            ? <input type="checkbox" className="pm-pick" aria-label={`Pick ${l.summary}`}
              title="Pick to shift or push this step" checked={picked} onChange={() => act.togglePick(l.ref)} />
            : <input type="checkbox" className="pm-pick pm-pick-sp" aria-hidden="true" tabIndex={-1} disabled />)}
          {inJira
            ? <button type="button" className="pm-key pm-key-open" aria-label={`Open ${key}`} title={`Sub-task ${key} in Jira: open its details`}
              onClick={() => act.openTicket(key)}>{key}</button>
            : <span className="pm-key new" title="Not in Jira yet: the push creates it">New</span>}
          <span className="pm-stp-name ellipsis" title={l.summary}>{st?.short ?? l.summary}</span>
          {l.pending && <span className="status-pill" style={{ background: 'var(--hl-amber)', color: 'var(--hl-amber-fg)' }}
            title="Changed here: sent to Jira with the push">Not pushed</span>}
          {l.closed && <span className="status-pill" title="Resolved or Closed in Jira: it cannot change here">Done</span>}
          <span className="spacer" />
          {!inJira && admin && l.edited && !l.removed && <IconButton icon="undo-2" label={`Reset ${l.summary}`} size="sm"
            title="Back to the effort of the estimation" onClick={() => void act.sendStep(l, { effort_h: null })} style={{ width: 26, height: 26 }} />}
          {!inJira && admin && !l.removed && (
            <button type="button" className="pm-del" aria-label={`Remove ${l.summary}`} title="Remove this step" onClick={() => void act.sendStep(l, { removed: true })}>
              <Icon name="trash-2" size={14} />
            </button>
          )}
          {!inJira && l.removed && <Button appearance="subtle" size="sm" aria-label={`Restore ${l.summary}`} disabled={!admin}
            onClick={() => void act.sendStep(l, { removed: false })}>Restore</Button>}
          {!inJira && l.removed && admin && l.row_id != null && <Button appearance="subtle" size="sm" aria-label={`Delete ${l.summary}`}
            title="Delete for good: it cannot be restored; a function left without a step is deleted"
            disabled={deleting} onClick={() => act.deleteStep(l)}>Delete</Button>}
        </span>
      </th>
      <td className={`pm-c1${marks.pic.length ? ' pm-bad' : ''}`}><span className="pm-cellm">{pic}<Mark level="error" msgs={marks.pic} /></span></td>
      <td className="pm-c2">{estimate}</td>
      <td className="pm-c3" aria-label={`Planned, ${l.summary}`}>{f2(l.plan_h)}</td>
      <td className={`pm-c4${marks.notYet.length ? ' pm-bad' : marks.notYetJira.length ? ' pm-warnc' : ''}`} aria-label={`Not yet plan, ${l.summary}`}
        data-left={left(l) || undefined}>
        <span className="pm-cellm"><Mark level="error" msgs={marks.notYet} /><Mark level="warning" msgs={marks.notYetJira} />{f2(l.plan_not_yet_h)}</span>
      </td>
      <td className="pm-c5" aria-label={`Start, ${l.summary}`}><span className="pm-cellm">{l.start ? dm(l.start) : '–'}<Mark level="error" msgs={marks.order} /><Mark level="warning" msgs={marks.orderJira} /></span></td>
      <td className="pm-c6" aria-label={`End, ${l.summary}`}>{l.end ? dm(l.end) : '–'}</td>
      <td className="pm-c7" aria-label={`Actual remaining, ${l.summary}`}>{l.actual_remaining_h == null ? '–' : f2(l.actual_remaining_h)}</td>
      {days.map((d) => {
        const planned = plan.get(d) ?? 0;
        const logged = logs.get(d) ?? 0;
        // every day of an open line can be planned, past ones too; the hours logged show under the plan
        const locked = !live;
        const warn = planned > 0 ? marks.overDay.filter((o) => o.date === d).map((o) => overDayText(o, name)) : [];
        return (
          <td key={d} data-day={d} data-out-of-order={(l.out_of_order && !l.removed) || undefined}
            className={`pm-cell${d === today ? ' pm-today' : ''}${locked ? ' locked' : ''}${l.out_of_order && !l.removed ? ' red' : ''}${warn.length ? ' warn' : ''}`}>
            <Mark level="warning" msgs={warn} />
            {locked
              ? <span className="pm-lk">{h(planned)}</span>
              : <DayCell label={`${l.summary} ${d}`} value={planned} hand={!inJira && typedDays.has(d)} send={(n) => act.sendCell(l, d, n)} />}
            {logged > 0 && <span className="muted pm-logged" title={`${h(logged)}h logged`}>{`${h(logged)}h`}</span>}
          </td>
        );
      })}
    </tr>
  );
});
