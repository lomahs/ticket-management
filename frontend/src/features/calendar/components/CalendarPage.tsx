import { useQuery } from '@tanstack/react-query';
import { getRouteApi, Outlet, useNavigate } from '@tanstack/react-router';
import { createContext, useContext, useState } from 'react';
import type { Schemas } from '../../../shared/api/types';
import { useMemberMap, useMeta } from '../../../shared/api/queries';
import type { LeaveTypeKey } from '../../../shared/api/types';
import { Button, IconButton } from '../../../shared/ds';
import { dm, dow, monLabel, shiftMon, WDN } from '../../../shared/format/dates';
import { HOLIDAY_BG, LEAVE, leaveBg } from '../../../shared/format/labels';
import { useToast } from '../../../shared/state/toast';
import { Avatar, av, PageHead } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { calendarQuery, exportUrl } from '../api';

const route = getRouteApi('/calendar');

/** Import wizard data shared by its step routes (the step itself is in the URL). */
export interface ImportState { file: string; rows: Schemas['ImportRow'][]; working_days: number }
const ImportCtx = createContext<[ImportState | null, (s: ImportState | null) => void]>([null, () => {}]);
export const useImportState = () => useContext(ImportCtx);

export function useCalendarMonth(): string {
  const today = useMeta().data?.today ?? new Date().toISOString().slice(0, 10);
  return route.useSearch({ select: (s) => s.month }) ?? today.slice(0, 7);
}

export function CalendarPage() {
  const month = useCalendarMonth();
  const navigate = useNavigate();
  const q = useQuery(calendarQuery(month));
  const toast = useToast();
  const importState = useState<ImportState | null>(null);
  const setMonth = (m: string) => navigate({ to: '/calendar', search: (p) => ({ ...p, month: m }) });
  const exportXls = () => {
    const a = document.createElement('a');
    a.href = exportUrl(month);
    document.body.appendChild(a);
    a.click();
    a.remove();
    const n = (q.data?.rows ?? []).reduce((s, r) => s + r.cells.filter((c) => c.kind === 'leave').length, 0);
    toast(`Exported ${n} leave days · ${monLabel(month)}`);
  };

  return (
    <ImportCtx.Provider value={importState}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14, width: '100%' }}>
        <PageHead eyebrow="Leave, WFH and holidays · feeds Planning, capacity and date suggestions" title="Team Calendar">
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <Button appearance="secondary" size="sm" iconBefore="file-up" onClick={() => navigate({ to: '/calendar/import/$step', params: { step: '1' }, search: (p) => p })}>Import Excel</Button>
            <Button appearance="secondary" size="sm" iconBefore="file-down" onClick={exportXls}>Export Excel</Button>
            <Button size="sm" iconBefore="plus" onClick={() => navigate({ to: '/calendar/leave/new', search: (p) => p })}>Add leave</Button>
          </div>
        </PageHead>
        <section className="card card-clip" aria-label="Team calendar">
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)' }}>
            <IconButton icon="chevron-left" label="Previous month" appearance="secondary" size="sm" onClick={() => setMonth(shiftMon(month, -1))} />
            <span style={{ fontWeight: 700, color: 'var(--text-heading)', minWidth: 120, textAlign: 'center' }}>{monLabel(month)}</span>
            <IconButton icon="chevron-right" label="Next month" appearance="secondary" size="sm" onClick={() => setMonth(shiftMon(month, 1))} />
            <span className="spacer" />
            <div className="legend">
              {(Object.keys(LEAVE) as (LeaveTypeKey | 'holiday')[]).map((k) => (
                <span key={k}>
                  <span style={{ minWidth: 26, height: 18, padding: '0 3px', boxSizing: 'border-box', borderRadius: 4, background: leaveBg(k), font: '700 9px/18px var(--font-text)', color: 'var(--text-heading)', textAlign: 'center' }}>{LEAVE[k].ab}</span>
                  {LEAVE[k].label}
                </span>
              ))}
            </div>
          </div>
          {q.isPending ? <Loading /> : q.isError ? <div style={{ padding: 16 }}><QueryError error={q.error} /></div> : <Grid v={q.data} />}
        </section>
      </div>
      <Outlet />
    </ImportCtx.Provider>
  );
}

function Grid({ v }: { v: Schemas['CalendarView'] }) {
  const members = useMemberMap();
  const navigate = useNavigate();
  const ti = v.days.findIndex((d) => d.is_today);
  const n = v.days.length;
  return (
    <div style={{ overflowX: 'auto' }}>
      <div style={{ width: '100%', minWidth: n * 28 + 230, position: 'relative' }}>
        {ti >= 0 && <div className="today-frame" style={{ left: `calc(170px + (100% - 230px) * ${(ti / n).toFixed(5)})`, width: `calc((100% - 230px) / ${n})` }} />}
        <div style={{ display: 'flex', borderBottom: '1px solid var(--border-subtle)' }}>
          <div className="sticky-col" style={{ width: 170, padding: '8px 14px', fontSize: 11, color: 'var(--text-muted)' }}>Member</div>
          {v.days.map((d) => (
            <div key={d.date} className="day-head" title={d.extra_note ? 'Extra working day · ' + d.extra_note : undefined} style={{
              flex: '1 1 0', minWidth: 0, padding: '4px 0', background: d.is_today ? 'var(--surface-tint)' : 'transparent',
              color: d.is_today ? 'var(--text-heading)' : d.extra_note ? 'var(--text-disabled)' : 'var(--text-muted)',
            }}>
              <div>{WDN[dow(d.date)][0]}</div><div className="n" style={{ fontWeight: d.is_today ? 700 : 500 }}>{d.date.slice(8, 10)}</div>
            </div>
          ))}
          <div style={{ width: 60, flex: 'none', boxSizing: 'border-box', padding: '8px 10px', borderLeft: '1px solid var(--border-subtle)', fontSize: 11, color: 'var(--text-muted)', textAlign: 'right' }}>Days off</div>
        </div>
        {v.rows.map((r) => {
          const m = members[r.member_id];
          return (
            <div key={r.member_id} style={{ display: 'flex', borderBottom: '1px solid var(--border-subtle)' }}>
              <div className="sticky-col" style={{ width: 170, padding: '6px 14px', display: 'flex', alignItems: 'center', gap: 8 }}>
                <Avatar a={av(m, r.member_id)} size={22} />
                <div>
                  <div style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{m?.name ?? r.member_id}</div>
                  <div style={{ fontSize: 10, color: 'var(--text-muted)' }}>{m?.role ?? '—'} · {m?.country}</div>
                </div>
              </div>
              {r.cells.map((c) => {
                const lv = c.leave;
                const tip = `${WDN[dow(c.date)]} ${dm(c.date)}${c.label ? ' · ' + c.label : ''}${lv?.note ? ' · ' + lv.note : ''}`;
                const click = () => (lv
                  ? navigate({ to: '/calendar/leave/$id', params: { id: String(lv.id) }, search: (p) => p })
                  : navigate({ to: '/calendar/leave/new', search: (p) => ({ ...p, member: r.member_id, date: c.date }) }));
                return (
                  <button key={c.date} className="hv-dim2" title={tip} aria-label={`${m?.name ?? r.member_id} ${tip}`} disabled={c.kind === 'holiday'} onClick={click}
                    style={{
                      flex: '1 1 0', minWidth: 0, boxSizing: 'border-box', height: 42, padding: 0, border: 0, borderLeft: '1px solid var(--border-subtle)',
                      background: c.kind === 'holiday' ? HOLIDAY_BG : lv ? leaveBg(lv.type as LeaveTypeKey) : 'transparent',
                      color: 'var(--text-heading)', font: '700 10px/1 var(--font-text)', cursor: c.kind === 'holiday' ? 'default' : 'pointer',
                    }}>{c.kind === 'holiday' ? 'H' : lv ? LEAVE[lv.type as LeaveTypeKey].ab : ''}</button>
                );
              })}
              <div style={{ width: 60, flex: 'none', boxSizing: 'border-box', padding: '0 10px', borderLeft: '1px solid var(--border-subtle)', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', fontWeight: 700, color: 'var(--text-heading)' }}>{r.days_off || '–'}</div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
