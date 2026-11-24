import { memo, use } from 'react';
import type { Schemas } from '../../../shared/api/types';
import { Icon } from '../../../shared/ds';
import { f2 } from '../../../shared/format/dates';
import type { MatrixLine } from './api';
import { hoursByDay } from './matrixModel';
import { LineActionsContext, Mark } from './StepLine';

type Row = Schemas['EstimationRow'];

/** A sum on a folded row: "–" for none. */
export const sumText = (n: number) => (Math.abs(n) < 0.005 ? '–' : f2(n));
const total = (lines: MatrixLine[], f: (l: MatrixLine) => number) => lines.reduce((a, l) => a + f(l), 0);

/**
 * Props equal when each is the same value, an array holding the same values counting as the same: the rows of the matrix get
 * arrays built again on every render of it (their lines, their messages), and render again only when what is in them changes.
 */
function sameProps<P extends object>(a: P, b: P): boolean {
  return (Object.keys(a) as (keyof P)[]).every((k) => {
    const x = a[k];
    const y = b[k];
    return x === y || (Array.isArray(x) && Array.isArray(y) && x.length === y.length && x.every((v, i) => v === y[i]));
  });
}

/** The day cells of a level: folded, its steps' hours per day (removed ones aside); empty while it is open. */
function SumCells({ lines, closed, days, today }: { lines: MatrixLine[]; closed: boolean; days: string[]; today: string }) {
  const per = closed ? lines.filter((l) => !l.removed).map((l) => hoursByDay(l.cells)) : [];
  return days.map((d) => {
    const t = per.reduce((a, m) => a + (m.get(d) ?? 0), 0);
    return <td key={d} className={`pm-cell pm-sum${d === today ? ' pm-today' : ''}${closed && t < 0.005 ? ' zero' : ''}`}>{closed ? sumText(t) : ''}</td>;
  });
}

function FoldButton({ k, closed, what }: { k: string; closed: boolean; what: string }) {
  const act = use(LineActionsContext);
  return (
    <button type="button" className={`pm-fold${closed ? ' closed' : ''}`} aria-expanded={!closed}
      aria-label={`${closed ? 'Show' : 'Hide'} ${what}`} title={`${closed ? 'Show' : 'Hide'} ${what}`} onClick={() => act.fold(k)}>
      <Icon name="chevron-down" size={14} />
    </button>
  );
}

/** A phase or a sub-phase (a Task): its name and count, what it says about the plan, and its steps' hours per day once folded. */
export const LevelRow = memo(function LevelRow({ level, k, name, count, meta, late, warnings, lines, closed, days, today }: {
  level: 'phase' | 'sub'; k: string; name: string; count: string; meta: string; late: boolean; warnings: string[]; lines: MatrixLine[];
  closed: boolean; days: string[]; today: string;
}) {
  const what = `the functions of ${name}`;
  return (
    <tr className={level === 'phase' ? 'pm-phase' : 'pm-sub'} aria-label={`${level === 'phase' ? 'Phase' : 'Sub-phase'} ${name}`}>
      <th scope="row" className="pm-c0">
        {level === 'phase'
          ? <span className="pm-ph"><FoldButton k={k} closed={closed} what={what} /><span className="pm-ph-name">{name}</span><span className="pm-badge">{count}</span><Mark level="warning" msgs={warnings} /></span>
          : <span className="pm-sb"><FoldButton k={k} closed={closed} what={what} /><Icon name="layers" size={13} color="var(--pm-today)" />
            <span className="pm-sb-name ellipsis" title={name}>{name}</span><span className="pm-badge">{count}</span></span>}
      </th>
      <td colSpan={4} className={`pm-meta${late ? ' late' : ''}`}>{meta}</td>
      <td className="pm-c5" /><td className="pm-c6" /><td className="pm-c7" />
      <SumCells lines={lines} closed={closed} days={days} today={today} />
    </tr>
  );
}, sameProps);

/**
 * A function: a row not in Jira yet (its order, its Task problems, its warnings) or a function in Jira; folded, the sums of its
 * steps.
 *
 * @param prev the row above it, which Move up swaps it with
 */
export const GroupRow = memo(function GroupRow({ k, name, row: r, lines, closed, picked, admin, issues, warnings, prev, next, days, today }: {
  k: string; name: string; row?: Row; lines: MatrixLine[]; closed: boolean; picked: boolean; admin: boolean; issues: string[];
  warnings: string[]; prev?: number; next?: number; days: string[]; today: string;
}) {
  const act = use(LineActionsContext);
  const live = lines.filter((l) => !l.removed);
  return (
    <tr className="pm-fn" aria-label={`Function ${name}`}>
      <th scope="row" className="pm-c0">
        <span className="pm-fnh">
          {admin && <input type="checkbox" className="pm-pick" aria-label={`Pick ${name}`} title="Pick to shift its plan"
            checked={picked} onChange={() => act.togglePick(k)} />}
          <FoldButton k={k} closed={closed} what={`the steps of ${name}`} />
          <span className="pm-fn-name ellipsis" title={name}>{name}</span>
          {r && <Mark level="error" msgs={issues} />}
          {r && <Mark level="warning" msgs={warnings} />}
          {!r && <span className="pm-badge">In Jira</span>}
          <span className="spacer" />
          {r && (
            <span className="pm-order" role="group" aria-label={`Order of ${name}`} title="Auto allocate plans the functions top to bottom">
              <Icon name="grip-vertical" size={13} />
              <button type="button" className="pm-ob" aria-label={`Move ${r.function_name} up`} title="Move up: Auto allocate plans it earlier"
                disabled={!admin || prev == null} onClick={() => { if (prev != null) act.swap(r.id, prev); }}><Icon name="arrow-up" size={13} /></button>
              <button type="button" className="pm-ob" aria-label={`Move ${r.function_name} down`} title="Move down: Auto allocate plans it later"
                disabled={!admin || next == null} onClick={() => { if (next != null) act.swap(r.id, next); }}><Icon name="arrow-down" size={13} /></button>
            </span>
          )}
        </span>
      </th>
      <td className="pm-c1" />
      <td className="pm-c2">{closed ? sumText(total(live, (l) => l.plan_estimate_h)) : ''}</td>
      <td className="pm-c3">{closed ? sumText(total(live, (l) => l.plan_h)) : ''}</td>
      <td className="pm-c4">{closed ? sumText(total(live.filter((l) => !l.closed), (l) => l.plan_not_yet_h)) : ''}</td>
      <td className="pm-c5" /><td className="pm-c6" /><td className="pm-c7" />
      <SumCells lines={lines} closed={closed} days={days} today={today} />
    </tr>
  );
}, sameProps);

/** The total per day of the lines shown (removed ones aside), with their Plan Estimation, Planned and Not yet plan. */
export const FootRow = memo(function FootRow({ lines, days, today }: { lines: MatrixLine[]; days: string[]; today: string }) {
  const per = lines.map((l) => hoursByDay(l.cells));
  return (
    <tr className="pm-foot">
      <th scope="row" className="pm-c0">Total per day</th>
      <td className="pm-c1" />
      <td className="pm-c2">{sumText(total(lines, (l) => l.plan_estimate_h))}</td>
      <td className="pm-c3">{sumText(total(lines, (l) => l.plan_h))}</td>
      <td className="pm-c4">{sumText(total(lines.filter((l) => !l.closed), (l) => l.plan_not_yet_h))}</td>
      <td className="pm-c5" /><td className="pm-c6" /><td className="pm-c7" />
      {days.map((d) => {
        const t = per.reduce((a, m) => a + (m.get(d) ?? 0), 0);
        return <td key={d} className={`pm-cell${d === today ? ' pm-today' : ''}${t < 0.005 ? ' zero' : ''}`}>{sumText(t)}</td>;
      })}
    </tr>
  );
}, sameProps);
