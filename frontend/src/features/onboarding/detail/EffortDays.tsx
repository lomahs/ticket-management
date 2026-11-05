import { useState, type CSSProperties } from 'react';
import type { Schemas } from '../../../shared/api/types';
import { f1 } from '../../../shared/format/dates';
import type { DetailPlanView } from './api';

type Day = Schemas['DayLoad'];

const CELL: CSSProperties = { padding: '4px 6px', borderBottom: '1px solid var(--border-subtle)', textAlign: 'center', whiteSpace: 'nowrap', fontSize: 12 };
const WEEKDAY = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

/**
 * Effort per member per day over the plan's window: hours planned on this anken (this plan and its open sub-tasks in Jira) /
 * the allocation on it, and below them the hours on other anken. Hovering a day lists its sub-tasks on this anken and the
 * other anken by name. Above the allocation is red, a day used up to it green; days off are greyed with their reason.
 */
export function EffortDays({ v, name }: { v: DetailPlanView; name: (id: string) => string }) {
  const [hover, setHover] = useState<string | null>(null);
  if (v.days.length === 0) return null;
  const dates = [...new Set(v.days.map((d) => d.date))].sort();
  const members = [...new Set(v.days.map((d) => d.member_id))];
  const at = new Map(v.days.map((d) => [`${d.member_id}|${d.date}`, d]));
  const tone = (planned: number, allocated: number, off: boolean): CSSProperties => {
    if (planned > allocated + 1e-9) return { background: 'var(--hl-red)', color: 'var(--hl-red-fg)', fontWeight: 700 };
    if (off) return { background: 'var(--surface-hover)', color: 'var(--text-muted)' };
    if (planned > 0 && planned >= allocated - 1e-9) return { background: 'var(--hl-ok)', fontWeight: 600 };
    return {};
  };
  return (
    <section className="card card-clip" aria-label="Effort per member per day">
      <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)', display: 'flex', alignItems: 'baseline', gap: 10 }}>
        <span className="title">Effort per member per day</span>
        <span className="eyebrow">Planned / allocated hours on this anken (this plan and its open sub-tasks in Jira); + hours on other anken below.
          Hover a day for its tasks. JM Review counts for nobody.</span>
      </div>
      <div style={{ overflowX: 'auto' }}>
        <table style={{ borderCollapse: 'collapse', color: 'var(--text-body)' }}>
          <thead>
            <tr>
              <th style={{ ...CELL, textAlign: 'left', position: 'sticky', left: 0, background: 'var(--surface-card)', zIndex: 1 }}>Member</th>
              {dates.map((d) => {
                const wd = new Date(`${d}T00:00:00`).getDay();
                return <th key={d} scope="col" style={{ ...CELL, color: wd === 0 || wd === 6 ? 'var(--text-muted)' : 'var(--text-heading)' }}>
                  {`${WEEKDAY[wd]} ${d.slice(8, 10)}/${d.slice(5, 7)}`}</th>;
              })}
            </tr>
          </thead>
          <tbody>
            {members.map((m) => (
              <tr key={m}>
                <th scope="row" style={{ ...CELL, textAlign: 'left', fontWeight: 600, position: 'sticky', left: 0, background: 'var(--surface-card)', zIndex: 1 }}>{name(m)}</th>
                {dates.map((d) => {
                  const x = at.get(`${m}|${d}`);
                  if (!x) return <td key={d} style={CELL} />;
                  const off = !!x.off;
                  const id = `${m}|${d}`;
                  const empty = x.items.length === 0;
                  return (
                    <td key={d} tabIndex={empty ? undefined : 0} aria-label={`${name(m)} ${d}`}
                      onMouseEnter={() => setHover(id)} onMouseLeave={() => setHover(null)} onFocus={() => setHover(id)} onBlur={() => setHover(null)}
                      style={{ ...CELL, ...tone(x.planned_h, off ? 0 : x.allocated_h, off), position: 'relative', cursor: empty ? undefined : 'default' }}>
                      {off && x.planned_h <= 0 && x.other_h <= 0 ? '' : `${x.planned_h > 0 ? f1(x.planned_h) : '–'} / ${f1(off ? 0 : x.allocated_h)}`}
                      {x.other_h > 0 && <div style={{ fontSize: 11, color: 'var(--text-muted)', fontWeight: 400 }}>{`+${f1(x.other_h)} other`}</div>}
                      {hover === id && !empty && <DayTasks d={x} title={`${name(m)} · ${d.slice(8, 10)}/${d.slice(5, 7)}${x.off ? ` · ${x.off}` : ''}`} />}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/** A day's tasks on this anken (Jira key when it is in Jira) and the other anken by name, with their hours. */
function DayTasks({ d, title }: { d: Day; title: string }) {
  const here = d.items.filter((i) => !i.other);
  const other = d.items.filter((i) => i.other);
  const line = (i: Day['items'][number], n: number) => (
    <div key={n} style={{ display: 'flex', gap: 8, justifyContent: 'space-between' }}>
      <span className="ellipsis" style={{ maxWidth: 320 }}>{i.key && !i.other ? `${i.key} · ${i.title}` : i.title}</span>
      <span style={{ fontWeight: 600 }}>{`${f1(i.hours)}h`}</span>
    </div>
  );
  return (
    <div role="tooltip" style={{
      position: 'absolute', top: '100%', left: 0, zIndex: 5, minWidth: 260, padding: '8px 10px', borderRadius: 8, textAlign: 'left',
      background: 'var(--surface-card)', border: '1px solid var(--border-subtle)', boxShadow: 'var(--shadow-raised, 0 8px 24px rgba(16,18,20,.12))',
      color: 'var(--text-body)', fontWeight: 400, display: 'flex', flexDirection: 'column', gap: 4,
    }}>
      <span className="eyebrow">{title}</span>
      {here.length > 0 && <span style={{ fontWeight: 600 }}>This anken</span>}
      {here.map(line)}
      {other.length > 0 && <span style={{ fontWeight: 600, marginTop: 4 }}>Other anken</span>}
      {other.map(line)}
    </div>
  );
}
