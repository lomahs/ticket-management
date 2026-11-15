import type { CSSProperties } from 'react';
import type { Schemas } from '../../../shared/api/types';
import { dm, hrs } from '../../../shared/format/dates';

type Row = Schemas['OverrunAllocation'];

const CELL: CSSProperties = { padding: '4px 10px', borderBottom: '1px solid var(--border-subtle)', textAlign: 'right', whiteSpace: 'nowrap' };
const HEAD: CSSProperties = { ...CELL, font: 'var(--fw-semibold) 12px/1.3 var(--font-text)', color: 'var(--text-muted)' };

/** What each member over budget is allocated to on the phase's Start: this anken first, then the others, hours a day. */
export function AllocationMatrix({ epic, rows, name }: { epic: string; rows: Row[]; name: (id: string) => string }) {
  const cols: { key: string; title: string }[] = [];
  for (const r of rows) {
    for (const a of r.ankens) {
      if (!cols.some((c) => c.key === a.epic_key)) cols.push({ key: a.epic_key, title: a.title });
    }
  }
  cols.sort((x, y) => (x.key === epic ? -1 : y.key === epic ? 1 : 0));
  return (
    <div style={{ overflowX: 'auto', marginTop: 6 }}>
      <table aria-label="Allocated per anken" style={{ borderCollapse: 'collapse', fontSize: 12, color: 'var(--text-body)' }}>
        <thead>
          <tr>
            <th style={{ ...HEAD, textAlign: 'left' }}>Member · phase</th>
            {cols.map((c) => (
              <th key={c.key} scope="col" title={c.title} style={{ ...HEAD, color: c.key === epic ? 'var(--text-heading)' : HEAD.color }}>
                {c.key}{c.key === epic ? ' (this)' : ''}
              </th>
            ))}
            <th scope="col" style={HEAD}>Total / day</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.member_id + r.phase}>
              <th scope="row" style={{ ...CELL, textAlign: 'left', fontWeight: 600 }}>{`${name(r.member_id)} · ${r.phase} (from ${dm(r.date)})`}</th>
              {cols.map((c) => {
                const a = r.ankens.find((x) => x.epic_key === c.key);
                return <td key={c.key} style={{ ...CELL, fontWeight: c.key === epic ? 600 : 400 }}>{a ? hrs(a.hours_per_day) : '–'}</td>;
              })}
              <td style={{ ...CELL, fontWeight: 600 }}>{hrs(r.ankens.reduce((s, a) => s + a.hours_per_day, 0))}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
