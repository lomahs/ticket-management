import { useEffect, useState, type CSSProperties } from 'react';
import { createPortal } from 'react-dom';
import type { Schemas } from '../../../shared/api/types';
import { dm, f1 } from '../../../shared/format/dates';
import type { DetailPlanView } from './api';

type Day = Schemas['DayLoad'];

const CELL: CSSProperties = { padding: '4px 6px', borderBottom: '1px solid var(--border-subtle)', textAlign: 'center', whiteSpace: 'nowrap', fontSize: 12 };
const WEEKDAY = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const weekday = (d: string) => new Date(`${d}T00:00:00`).getDay();

/** The hovered day and where its cell is on screen: its tasks open above everything, not clipped by the section. */
interface Hover { id: string; rect: DOMRect }

/**
 * Effort per member per day over the plan's window, Monday to Friday: hours planned on this anken (this plan and its open
 * sub-tasks in Jira), and below them the hours on other anken. Hovering a day lists its sub-tasks on this anken and the
 * other anken by name. Above the allocation is red, a day used up to it green; days off are greyed with their reason.
 */
export function EffortDays({ v, name }: { v: DetailPlanView; name: (id: string) => string }) {
  const [hover, setHover] = useState<Hover | null>(null);
  useEffect(() => { // the tasks stay where the cell was: they close when anything scrolls
    if (!hover) return;
    const close = () => setHover(null);
    window.addEventListener('scroll', close, true);
    window.addEventListener('resize', close);
    return () => { window.removeEventListener('scroll', close, true); window.removeEventListener('resize', close); };
  }, [hover]);
  if (v.days.length === 0) return null;
  const dates = [...new Set(v.days.map((d) => d.date))].filter((d) => weekday(d) !== 0 && weekday(d) !== 6).sort();
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
        <span className="eyebrow">Planned hours on this anken (this plan and its open sub-tasks in Jira), red above the allocation; + hours on
          other anken below. Hover a day for its tasks. JM Review counts for nobody.</span>
      </div>
      <div style={{ overflowX: 'auto' }}>
        <table style={{ borderCollapse: 'collapse', color: 'var(--text-body)' }}>
          <thead>
            <tr>
              <th rowSpan={2} style={{ ...CELL, textAlign: 'left', position: 'sticky', left: 0, background: 'var(--surface-card)', zIndex: 1 }}>Member</th>
              {dates.map((d) => <th key={d} scope="col" style={{ ...CELL, borderBottom: 'none', paddingBottom: 0, color: 'var(--text-muted)', fontWeight: 500 }}>
                {WEEKDAY[weekday(d)]}</th>)}
            </tr>
            <tr>
              {dates.map((d) => <th key={d} scope="col" aria-label={d} style={{ ...CELL, paddingTop: 0, color: 'var(--text-heading)' }}>
                {dm(d)}</th>)}
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
                      onMouseEnter={(e) => setHover({ id, rect: e.currentTarget.getBoundingClientRect() })} onMouseLeave={() => setHover(null)}
                      onFocus={(e) => setHover({ id, rect: e.currentTarget.getBoundingClientRect() })} onBlur={() => setHover(null)}
                      style={{ ...CELL, ...tone(x.planned_h, off ? 0 : x.allocated_h, off), cursor: empty ? undefined : 'default' }}>
                      {off && x.planned_h <= 0 && x.other_h <= 0 ? '' : x.planned_h > 0 ? f1(x.planned_h) : '–'}
                      {x.other_h > 0 && <div style={{ fontSize: 11, color: 'var(--text-muted)', fontWeight: 400 }}>{`+${f1(x.other_h)} other`}</div>}
                      {hover?.id === id && !empty && <DayTasks d={x} at={hover.rect} title={`${name(m)} · ${dm(d)}${x.off ? ` · ${x.off}` : ''}`} />}
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

const TIP_W = 340;

/**
 * A day's tasks on this anken (Jira key when it is in Jira) and the other anken by name, with their hours: on top of the page,
 * under the cell {@code at}, or above it near the bottom of the window; kept inside the window's width.
 */
function DayTasks({ d, title, at }: { d: Day; title: string; at: DOMRect }) {
  const here = d.items.filter((i) => !i.other);
  const other = d.items.filter((i) => i.other);
  const line = (i: Day['items'][number], n: number) => (
    <div key={n} style={{ display: 'flex', gap: 8, justifyContent: 'space-between' }}>
      <span className="ellipsis" style={{ maxWidth: TIP_W - 80 }}>{i.key && !i.other ? `${i.key} · ${i.title}` : i.title}</span>
      <span style={{ fontWeight: 600 }}>{`${f1(i.hours)}h`}</span>
    </div>
  );
  const below = at.bottom < window.innerHeight * 0.6;
  return createPortal(
    <div role="tooltip" style={{
      position: 'fixed', zIndex: 60, left: Math.max(8, Math.min(at.left, window.innerWidth - TIP_W - 8)),
      ...(below ? { top: at.bottom + 4 } : { bottom: window.innerHeight - at.top + 4 }),
      width: 'max-content', minWidth: 260, maxWidth: TIP_W, boxSizing: 'border-box', padding: '8px 10px', borderRadius: 8, textAlign: 'left', pointerEvents: 'none', fontSize: 12,
      background: 'var(--surface-card)', border: '1px solid var(--border-subtle)', boxShadow: 'var(--shadow-raised, 0 8px 24px rgba(16,18,20,.12))',
      color: 'var(--text-body)', fontWeight: 400, display: 'flex', flexDirection: 'column', gap: 4,
    }}>
      <span className="eyebrow">{title}</span>
      {here.length > 0 && <span style={{ fontWeight: 600 }}>This anken</span>}
      {here.map(line)}
      {other.length > 0 && <span style={{ fontWeight: 600, marginTop: 4 }}>Other anken</span>}
      {other.map(line)}
    </div>,
    document.body,
  );
}
