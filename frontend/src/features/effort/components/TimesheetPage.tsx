import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { useState, type MouseEvent } from 'react';
import type { Schemas } from '../../../shared/api/types';
import { useMemberMap, useMeta } from '../../../shared/api/queries';
import { IconButton } from '../../../shared/ds';
import { add, dm, dow, f1, hrs, monLabel, monthDays, mondayOf, shiftMon, WDN } from '../../../shared/format/dates';
import { dayHeadStyle, hatch, stepLabel } from '../../../shared/format/labels';
import { useOpenTicket } from '../../../shared/state/ticket';
import { Avatar, av, Legend, Seg } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { dayDetailQuery, timesheetQuery } from '../api';
import { EffortHeader } from './EffortHeader';

const route = getRouteApi('/effort/timesheet');
const PER_SCREEN = 10;
interface Pop { member: string; date: string; top: number; left: number; pinned: boolean }

const CELL: Record<string, { bg: string; fg: string }> = {
  weekend: { bg: 'var(--surface-hover)', fg: 'var(--text-disabled)' },
  off: { bg: hatch('var(--border-subtle)') + ',var(--surface-hover)', fg: 'var(--text-disabled)' },
  over: { bg: 'var(--hl-red)', fg: 'var(--hl-red-fg)' },
  ok: { bg: 'var(--hl-ok)', fg: 'var(--text-heading)' },
};

export function TimesheetPage() {
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const today = useMeta().data?.today ?? new Date().toISOString().slice(0, 10);
  const view = search.view ?? 'week';
  const isWeek = view === 'week';
  const from = isWeek ? mondayOf(search.from ?? today) : (search.month ?? today.slice(0, 7)) + '-01';
  const to = isWeek ? add(from, 6) : monthDays(from.slice(0, 7)).at(-1)!;
  const q = useQuery(timesheetQuery(from, to));
  const step = (dir: 1 | -1) => navigate({
    search: (p) => (isWeek ? { ...p, from: add(from, 7 * dir) } : { ...p, month: shiftMon(from.slice(0, 7), dir) }),
  });
  const setView = (v: 'week' | 'month') => navigate({ search: (p) => ({ ...p, view: v, from: v === 'week' ? from : undefined, month: v === 'month' ? from.slice(0, 7) : undefined }) });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, width: '100%' }}>
      <EffortHeader tab="timesheet" />
      <section className="card card-clip" aria-label="Timesheet">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)' }}>
          <div style={{ marginRight: 6 }}><Seg items={[['week', 'Week'], ['month', 'Month']]} value={view} onPick={setView} /></div>
          <IconButton icon="chevron-left" label="Previous" appearance="secondary" size="sm" onClick={() => step(-1)} />
          <span style={{ fontWeight: 700, color: 'var(--text-heading)', minWidth: 120, textAlign: 'center' }}>
            {isWeek ? `${dm(from)} – ${dm(to)}/${to.slice(0, 4)}` : monLabel(from.slice(0, 7))}
          </span>
          <IconButton icon="chevron-right" label="Next" appearance="secondary" size="sm" onClick={() => step(1)} />
          <span className="spacer" />
          <div className="legend">
            <Legend items={[{ label: 'Actual ≤ plan', bg: 'var(--hl-ok)' }, { label: 'Actual > plan', bg: 'var(--hl-red)' }, { label: 'Day off', bg: 'var(--surface-hover)' }]} />
            <span>Hover a cell for worklogs · click to pin</span>
          </div>
        </div>
        {q.isPending ? <Loading /> : q.isError ? <div style={{ padding: 16 }}><QueryError error={q.error} /></div> : <Grid v={q.data} fit={isWeek || q.data.days.length <= PER_SCREEN} />}
      </section>
    </div>
  );
}

function Grid({ v, fit }: { v: Schemas['TimesheetView']; fit: boolean }) {
  const members = useMemberMap();
  const [hover, setHover] = useState<Pop | null>(null);
  const [pin, setPin] = useState<Pop | null>(null);
  const n = v.days.length;
  const ti = v.days.findIndex((d) => d.is_today);
  const pos = (e: MouseEvent<HTMLElement>, member: string, date: string, pinned: boolean): Pop => {
    const r = e.currentTarget.getBoundingClientRect();
    return { member, date, pinned, top: Math.max(8, Math.min(r.bottom + 6, innerHeight - 360)), left: Math.max(8, Math.min(r.left + r.width / 2 - 180, innerWidth - 376)) };
  };
  const pop = pin ?? hover;
  return (
    <>
      <div style={{ overflowX: 'auto' }}>
        <div style={{ width: fit ? '100%' : `calc((100% - 260px) * ${(n / PER_SCREEN).toFixed(4)} + 260px)`, minWidth: 560, position: 'relative' }}>
          {ti >= 0 && <div className="today-frame" style={{ left: `calc(150px + (100% - 260px) * ${(ti / n).toFixed(5)})`, width: `calc((100% - 260px) / ${n})` }} />}
          <div style={{ display: 'flex', borderBottom: '1px solid var(--border-subtle)' }}>
            <div className="sticky-col" style={{ width: 150, padding: '8px 14px', fontSize: 11, color: 'var(--text-muted)' }}>Member</div>
            {v.days.map((d) => {
              const h = dayHeadStyle(d);
              return (
                <div key={d.date} className="day-head" title={h.tip} style={{ flex: '1 1 0', minWidth: 0, padding: '4px 0', background: h.bg, color: h.fg }}>
                  <div>{WDN[dow(d.date)]}</div><div className="n" style={{ fontWeight: h.fw }}>{d.date.slice(8, 10)}</div>
                </div>
              );
            })}
            <div style={{ width: 110, flex: 'none', boxSizing: 'border-box', padding: '8px 12px', borderLeft: '1px solid var(--border-subtle)', fontSize: 11, color: 'var(--text-muted)', textAlign: 'right' }}>Logged</div>
          </div>
          {v.rows.map((r) => {
            const m = members[r.member_id];
            return (
              <div key={r.member_id} style={{ display: 'flex', borderBottom: '1px solid var(--border-subtle)' }}>
                <div className="sticky-col" style={{ width: 150, padding: '6px 14px', display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Avatar a={av(m, r.member_id)} size={22} />
                  <div style={{ minWidth: 0 }}>
                    <div style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{m?.name ?? r.member_id}</div>
                    <div style={{ fontSize: 10, color: r.over_days ? 'var(--hl-red-fg)' : 'var(--text-muted)', whiteSpace: 'nowrap' }}>
                      {m?.role ?? '—'}{r.over_days ? ` · ${r.over_days} days over plan` : ''}
                    </div>
                  </div>
                </div>
                {r.cells.map((c) => {
                  const s = CELL[c.state] ?? { bg: 'transparent', fg: 'var(--text-heading)' };
                  const clickable = c.state !== 'future' && (c.logged_h > 0 || (c.state !== 'weekend' && c.state !== 'off'));
                  const value = c.state === 'off' ? 'off' : c.state === 'future' || c.state === 'weekend' || !c.logged_h ? '' : hrs(c.logged_h);
                  const planned = c.planned_h ? `plan ${hrs(c.planned_h)}` : c.state === 'weekend' || c.state === 'off' ? '' : 'plan –';
                  return (
                    <button key={c.date} className="hv-dim2"
                      title={`${m?.name ?? r.member_id} · ${WDN[dow(c.date)]} ${dm(c.date)} · ${hrs(c.logged_h)} logged${c.off_reason ? ' · ' + c.off_reason : ''}`}
                      onClick={(e) => clickable && setPin(pos(e, r.member_id, c.date, true))}
                      onMouseEnter={(e) => clickable && !pin && setHover(pos(e, r.member_id, c.date, false))}
                      onMouseLeave={() => setHover(null)}
                      style={{
                        flex: '1 1 0', minWidth: 0, boxSizing: 'border-box', height: 46, padding: '0 2px', border: 0, borderLeft: '1px solid var(--border-subtle)',
                        background: s.bg, color: s.fg, font: '600 12px/1 var(--font-text)', cursor: clickable ? 'pointer' : 'default',
                        display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 4, overflow: 'hidden',
                      }}>
                      <span>{value}</span>
                      <span style={{ font: '500 10px/1 var(--font-text)', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>{planned}</span>
                    </button>
                  );
                })}
                <div style={{ width: 110, flex: 'none', boxSizing: 'border-box', padding: '6px 12px', borderLeft: '1px solid var(--border-subtle)', textAlign: 'right', display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
                  <span style={{ fontWeight: 700, color: r.logged_h < r.expected_h ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{hrs(r.logged_h)}</span>
                  <span style={{ fontSize: 10, color: 'var(--text-muted)' }}>of {hrs(r.expected_h)}</span>
                </div>
              </div>
            );
          })}
        </div>
      </div>
      {pop && (
        <>
          {pin && <div onClick={() => setPin(null)} style={{ position: 'fixed', inset: 0, zIndex: 55 }} />}
          <DayPopover pop={pop} close={() => setPin(null)} />
        </>
      )}
    </>
  );
}

function DayPopover({ pop, close }: { pop: Pop; close: () => void }) {
  const q = useQuery(dayDetailQuery(pop.member, pop.date));
  const members = useMemberMap();
  const open = useOpenTicket();
  const d = q.data;
  return (
    <div role="dialog" aria-label="Worklogs" style={{
      position: 'fixed', top: pop.top, left: pop.left, zIndex: 56, pointerEvents: pop.pinned ? 'auto' : 'none', width: 360, maxHeight: 340, overflow: 'auto',
      borderRadius: 14, background: 'var(--surface-card)', border: '1px solid var(--border-subtle)', boxShadow: '0 12px 32px rgba(9,30,66,0.22)',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '12px 14px', borderBottom: '1px solid var(--border-subtle)' }}>
        <span className="title" style={{ flex: 1 }}>{members[pop.member]?.name ?? pop.member} · {WDN[dow(pop.date)]} {dm(pop.date)}</span>
        {d && <><b className="heading">{hrs(d.logged_h)} actual</b><span style={{ fontSize: 11, color: 'var(--text-muted)' }}>· {hrs(d.planned_h)} plan</span></>}
      </div>
      {q.isPending && <Loading />}
      {d?.entries.map((e) => (
        <div key={e.key} className="hv-bg" role="button" tabIndex={0} onClick={() => { close(); open(e.key); }}
          style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) auto', gap: '2px 10px', padding: '9px 14px', borderBottom: '1px solid var(--border-subtle)', cursor: 'pointer' }}>
          <span style={{ font: '600 11px/1.3 var(--font-mono)', color: 'var(--text-link)' }}>{e.key}</span>
          <b className="heading" style={{ textAlign: 'right' }}>{hrs(e.logged_h)}</b>
          <span className="ellipsis heading" style={{ fontSize: 12 }}>{e.function_id ? `${e.function_id} · ${stepLabel(e.step)}` : e.summary}</span>
          <span style={{ fontSize: 11, color: 'var(--text-muted)', textAlign: 'right', whiteSpace: 'nowrap' }}>plan {e.planned_h ? f1(e.planned_h) + 'h' : '–'}</span>
          <span style={{ fontSize: 11, color: 'var(--text-muted)', gridColumn: '1 / -1' }}>{e.notes.join(' · ') || 'No worklog yet'}</span>
        </div>
      ))}
      {d && !d.entries.length && <div style={{ padding: '12px 14px', fontSize: 12, color: 'var(--hl-red-fg)' }}>No worklogs on this working day.</div>}
    </div>
  );
}
