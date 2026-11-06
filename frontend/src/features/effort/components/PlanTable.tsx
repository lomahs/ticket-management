import type { CSSProperties } from 'react';
import type { Schemas } from '../../../shared/api/types';
import { useMemberMap } from '../../../shared/api/queries';
import { dm, f1 } from '../../../shared/format/dates';

type Row = Schemas['PlanRow'];

const CELL: CSSProperties = { padding: '4px 8px', borderBottom: '1px solid var(--border-subtle)', textAlign: 'right', whiteSpace: 'nowrap', fontSize: 12 };
// Phase, Sub-phase, Function and PIC stay in view while the days scroll
const FIXED = [80, 170, 200, 130];
const fixed = (n: number): CSSProperties => ({
  ...CELL, textAlign: 'left', position: 'sticky', left: FIXED.slice(0, n).reduce((a, b) => a + b, 0), zIndex: 1,
  width: FIXED[n], minWidth: FIXED[n], maxWidth: FIXED[n], overflow: 'hidden', textOverflow: 'ellipsis', background: 'var(--surface-card)',
  ...(n === FIXED.length - 1 ? { borderRight: '1px solid var(--border-subtle)' } : {}),
});
const hours = (h: number) => (h > 0.005 ? f1(h) : '—');

/**
 * The planned hours per function, person and day (Day 1 is the first working day of the scope): each step's estimate shared
 * between the function's BA and BAL by Settings › Step weights, spread over the person's working days of its Planned Start–End.
 * A phase, sub-phase or function is named on its first row only.
 */
export function PlanTable({ m, today }: { m: Schemas['PlanGrid']; today: string }) {
  const members = useMemberMap();
  if (m.rows.length === 0) return null;
  const pic = (id: string) => {
    const p = members[id];
    return p ? (p.role ? `${p.name} (${p.role})` : p.name) : id;
  };
  const same = (a: Row | undefined, b: Row, depth: number) => !!a && a.phase === b.phase && (depth < 2 || a.task_key === b.task_key)
    && (depth < 3 || a.function_key === b.function_key);
  const now = (d: string): CSSProperties => (d === today ? { background: 'var(--surface-hover)' } : {});
  const byDay = m.days.map((_, n) => m.rows.reduce((a, r) => a + r.hours[n], 0));
  return (
    <section className="card card-clip">
      <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)', display: 'flex', alignItems: 'baseline', gap: 10, flexWrap: 'wrap' }}>
        <span className="title">Planned effort per day</span>
        <span className="eyebrow">Hours per function and person on each working day: each sub-task's estimate shared between the BA and
          the BAL by Settings › Step weights, spread over their working days from Planned Start to Planned End. JM Review counts for nobody.</span>
      </div>
      <div style={{ overflowX: 'auto' }}>
        <table aria-label="Planned effort per function, person and day" style={{ borderCollapse: 'separate', borderSpacing: 0, color: 'var(--text-body)' }}>
          <thead>
            <tr>
              {['Phase', 'Sub-phase', 'Function', 'PIC'].map((h, n) => <th key={h} scope="col" style={{ ...fixed(n), color: 'var(--text-muted)', fontWeight: 500 }}>{h}</th>)}
              {m.days.map((d, n) => (
                <th key={d} scope="col" aria-label={d} style={{ ...CELL, ...now(d), fontWeight: 500, color: d === today ? 'var(--border-focus)' : 'var(--text-muted)' }}>
                  <div>{`Day ${n + 1}`}</div>
                  <div style={{ color: d === today ? 'var(--border-focus)' : 'var(--text-heading)', fontWeight: 600 }}>{dm(d)}</div>
                </th>
              ))}
              <th scope="col" style={{ ...CELL, color: 'var(--text-muted)', fontWeight: 500 }}>Total</th>
            </tr>
          </thead>
          <tbody>
            {m.rows.map((r, i) => {
              const prev = m.rows[i - 1];
              return (
                <tr key={`${r.task_key}|${r.function_key}|${r.member_id}`}>
                  <td style={fixed(0)} title={r.phase}>{same(prev, r, 1) ? '' : r.phase}</td>
                  <td style={fixed(1)} title={r.task}>{same(prev, r, 2) ? '' : r.task}</td>
                  <td style={{ ...fixed(2), fontWeight: 600, color: 'var(--text-heading)' }} title={r.function}>{same(prev, r, 3) ? '' : r.function}</td>
                  <td style={fixed(3)}>{pic(r.member_id)}</td>
                  {r.hours.map((h, n) => <td key={m.days[n]} style={{ ...CELL, ...now(m.days[n]), color: h > 0.005 ? undefined : 'var(--text-muted)' }}>{hours(h)}</td>)}
                  <td style={{ ...CELL, fontWeight: 600 }}>{hours(r.total_h)}</td>
                </tr>
              );
            })}
          </tbody>
          <tfoot>
            <tr style={{ fontWeight: 700, color: 'var(--text-heading)' }}>
              <th scope="row" style={fixed(0)}>Total</th>
              <td style={fixed(1)} />
              <td style={fixed(2)} />
              <td style={fixed(3)} />
              {byDay.map((h, n) => <td key={m.days[n]} style={{ ...CELL, ...now(m.days[n]) }}>{hours(h)}</td>)}
              <td style={CELL}>{hours(m.rows.reduce((a, r) => a + r.total_h, 0))}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </section>
  );
}
