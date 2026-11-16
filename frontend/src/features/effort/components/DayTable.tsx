import { useQuery } from '@tanstack/react-query';
import type { CSSProperties } from 'react';
import type { Schemas } from '../../../shared/api/types';
import { useMemberMap } from '../../../shared/api/queries';
import { D, dm, f2, WDN } from '../../../shared/format/dates';
import { stepLabel } from '../../../shared/format/labels';
import { useOpenTicket } from '../../../shared/state/ticket';
import { QueryError } from '../../../shared/ui/states';
import { burndownDayQuery } from '../api';

type Row = Schemas['BurndownTicket'];

const CELL: CSSProperties = { padding: '6px 10px', borderBottom: '1px solid var(--border-subtle)', textAlign: 'right', whiteSpace: 'nowrap', fontSize: 12 };
const TEXT: CSSProperties = { ...CELL, textAlign: 'left', maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis' };
const hours = (h: number | null) => (h == null ? '–' : f2(h));
const DIFF: CSSProperties = { background: 'var(--hl-amber)', color: 'var(--hl-amber-fg)', fontWeight: 600 };

/** A plan cell and its actual cell, highlighted both when they differ (to the hundredth); nothing actual yet: as they are. */
function pair(plan: number, actual: number | null): { style: CSSProperties; title?: string; 'data-diff'?: true } {
  const gap = actual == null ? 0 : Math.round((actual - plan) * 100) / 100;
  if (gap === 0) return { style: CELL };
  return { style: { ...CELL, ...DIFF }, title: `Actual − Plan: ${gap > 0 ? '+' : '−'}${f2(Math.abs(gap))}h`, 'data-diff': true };
}
const sum = (rows: Row[], f: (r: Row) => number | null) => rows.reduce((a, r) => a + (f(r) ?? 0), 0);

/** Where a sub-task's Actual Remain effort comes from (docs/rules.md: the actual remaining of a day). */
const SOURCE: Record<NonNullable<Row['source']>, [string, string]> = {
  jira: ['Jira now', "Jira's Remaining Estimate now"],
  capture: ['Capture', "The Daily Overview capture of that day, plus the Original Estimate changes approved since"],
  changelog: ['Jira history', "No capture of that day: the Remaining Estimate at the end of that day in Jira's history, plus the Original Estimate changes approved since"],
  worklogs: ['From worklogs', 'No capture or Jira history of that day: the Remaining Estimate now plus the hours logged after that day'],
};

/**
 * The sub-tasks of the burndown at the end of `day`, from the start: their Total is the chart's point of that day. Plan Remain
 * effort is the Estimate less the Plan effort; Actual Remain effort is Jira's Remaining Estimate of that day, with its source.
 * A sub-phase or function is named on its first row only.
 */
export function DayTable({ anken, filters, day }: { anken: string; filters: { phase?: string; task?: string; member?: string }; day: string }) {
  const q = useQuery(burndownDayQuery(filters, anken, day));
  const members = useMemberMap();
  const open = useOpenTicket();
  if (q.isError) return <QueryError error={q.error} />;
  const rows = q.data?.rows ?? [];
  const future = !!q.data && q.data.date > q.data.today;
  const pic = (id: string | null) => {
    const p = id ? members[id] : undefined;
    return p ? (p.role ? `${p.name} (${p.role})` : p.name) : id ?? '–';
  };
  // after today there is nothing actual yet, as on the chart
  const total = (f: (r: Row) => number | null) => (future ? '–' : f2(sum(rows, f)));
  const totalEffort = pair(sum(rows, (r) => r.planned_h), future ? null : sum(rows, (r) => r.logged_h));
  const totalRemain = pair(sum(rows, (r) => r.plan_remaining_h), future ? null : sum(rows, (r) => r.actual_remaining_h));
  return (
    <section className="card card-clip" aria-busy={q.isFetching || undefined}>
      <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)', display: 'flex', alignItems: 'baseline', gap: 10, flexWrap: 'wrap' }}>
        <span className="title">{`Effort by ticket · ${WDN[D(day).getUTCDay()]} ${dm(day)}`}</span>
        <span className="eyebrow">From the start to the end of the day; click a day on the chart to show it. Plan Remain = Estimate − Plan effort.
          Actual Remain is Jira&apos;s Remaining Estimate of that day: the Total is the chart&apos;s point. Highlighted: the actual differs
          from the plan.</span>
      </div>
      <div style={{ overflowX: 'auto' }}>
        <table aria-label="Effort by ticket" style={{ width: '100%', borderCollapse: 'separate', borderSpacing: 0, color: 'var(--text-body)' }}>
          <thead>
            <tr>
              {['Sub-Phase', 'Function', 'Step', 'Ticket', 'PIC'].map((h) => <th key={h} scope="col" style={{ ...TEXT, color: 'var(--text-muted)', fontWeight: 500 }}>{h}</th>)}
              {['Estimate', 'Plan effort', 'Actual effort', 'Plan Remain effort', 'Actual Remain effort'].map((h) => (
                <th key={h} scope="col" style={{ ...CELL, color: 'var(--text-muted)', fontWeight: 500 }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr><td colSpan={10} className="empty-row" style={{ fontSize: 13 }}>{q.isPending ? 'Loading…' : 'No sub-tasks in the chart.'}</td></tr>
            )}
            {rows.map((r, i) => {
              const prev = rows[i - 1];
              const sameTask = !!prev && prev.task_key === r.task_key;
              const src = r.source ? SOURCE[r.source] : null;
              const effort = pair(r.planned_h, r.logged_h);
              const remain = pair(r.plan_remaining_h, r.actual_remaining_h);
              return (
                <tr key={r.key}>
                  <td style={TEXT} title={r.task}>{sameTask ? '' : r.task}</td>
                  <td style={{ ...TEXT, fontWeight: 600, color: 'var(--text-heading)' }} title={r.function}>{sameTask && prev.function === r.function ? '' : r.function}</td>
                  <td style={TEXT}>{stepLabel(r.step) || '–'}</td>
                  <td style={TEXT}>
                    <button type="button" className="link" onClick={() => open(r.key)} title={`Open ${r.key}`}
                      style={{ border: 0, padding: 0, background: 'none', font: '600 12px/1 var(--font-mono)', color: 'var(--text-link)', cursor: 'pointer' }}>{r.key}</button>
                  </td>
                  <td style={TEXT}>{pic(r.pic_id)}</td>
                  <td style={CELL}>{hours(r.estimate_h)}</td>
                  <td {...effort}>{hours(r.planned_h)}</td>
                  <td {...effort}>{hours(r.logged_h)}</td>
                  <td {...remain}>{hours(r.plan_remaining_h)}</td>
                  <td {...remain}>
                    {hours(r.actual_remaining_h)}
                    {src && <span className="muted" title={src[1]} style={{ display: 'block', fontSize: 10 }}>{src[0]}</span>}
                  </td>
                </tr>
              );
            })}
          </tbody>
          {rows.length > 0 && (
            <tfoot>
              <tr style={{ fontWeight: 700, color: 'var(--text-heading)' }}>
                <th scope="row" style={TEXT}>Total</th>
                <td style={TEXT} /><td style={TEXT} /><td style={TEXT} /><td style={TEXT} />
                <td style={CELL}>{f2(sum(rows, (r) => r.estimate_h))}</td>
                <td {...totalEffort}>{f2(sum(rows, (r) => r.planned_h))}</td>
                <td {...totalEffort}>{total((r) => r.logged_h)}</td>
                <td {...totalRemain}>{f2(sum(rows, (r) => r.plan_remaining_h))}</td>
                <td {...totalRemain}>{total((r) => r.actual_remaining_h)}</td>
              </tr>
            </tfoot>
          )}
        </table>
      </div>
    </section>
  );
}
