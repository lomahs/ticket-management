import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { useState, type MouseEvent, type ReactNode } from 'react';
import type { Schemas } from '../../../shared/api/types';
import { useMemberMap, useMeta } from '../../../shared/api/queries';
import { useAnkenScope } from '../../../shared/state/anken';
import { Icon, IconButton } from '../../../shared/ds';
import { add, dm, dow, f2, hrs, mondayOf, monLabel, monthDays, shiftMon, WDN, ymdSpan } from '../../../shared/format/dates';
import { dayHeadStyle, stepLabel } from '../../../shared/format/labels';
import { useOpenTicket } from '../../../shared/state/ticket';
import { Avatar, av, Seg } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { dayDetailQuery, timesheetQuery } from '../api';
import { EffortHeader } from './EffortHeader';

const route = getRouteApi('/app/effort/timesheet');
const PER_SCREEN = 10;
/** The member column and the Week total column, in px: the days share what is left. */
const MEMBER_W = 212;
const TOTAL_W = 150;
const SIDE = MEMBER_W + TOTAL_W;
const STATE_LABEL: Record<string, string> = { ok: 'On allocation', under: 'Under allocation', over: 'Over allocation', no_allocation: 'No allocation' };
type Show = 'over' | 'under' | 'no_allocation';
type Row = Schemas['TimesheetView']['rows'][number];
type Cell = Row['cells'][number];
interface Pop { member: string; date: string; top: number; left: number; pinned: boolean }

const SHOWS: { key: Show; label: string; mark: ReactNode; has: (r: Row) => boolean }[] = [
  { key: 'over', label: 'Over-allocated', mark: <Icon name="triangle-alert" size={12} strokeWidth={2.4} />, has: (r) => r.over_days > 0 },
  { key: 'under', label: 'Under-allocated', mark: <span className="ts-dot under" />, has: (r) => r.under_days > 0 },
  { key: 'no_allocation', label: 'No allocation', mark: <span className="ts-dot no_allocation" />, has: (r) => r.cells.some((c) => c.state === 'no_allocation') },
];

export function TimesheetPage() {
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const today = useMeta().data?.today ?? new Date().toISOString().slice(0, 10);
  const view = search.view ?? 'week';
  const isWeek = view === 'week';
  const from = isWeek ? mondayOf(search.from ?? today) : (search.month ?? today.slice(0, 7)) + '-01';
  const to = isWeek ? add(from, 6) : monthDays(from.slice(0, 7)).at(-1)!;
  const { anken } = useAnkenScope();
  const q = useQuery(timesheetQuery(from, to, anken));
  const step = (dir: 1 | -1) => navigate({
    search: (p) => (isWeek ? { ...p, from: add(from, 7 * dir) } : { ...p, month: shiftMon(from.slice(0, 7), dir) }),
  });
  const setView = (v: 'week' | 'month') => navigate({ search: (p) => ({ ...p, view: v, from: v === 'week' ? from : undefined, month: v === 'month' ? from.slice(0, 7) : undefined }) });
  const setShow = (show: Show | undefined) => navigate({ search: (p) => ({ ...p, show }), replace: true });
  const rows = q.data?.rows ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, width: '100%' }}>
      <EffortHeader tab="timesheet" />
      <section className="card card-clip ts" aria-label="Timesheet">
        <div className="ts-bar">
          <div style={{ marginRight: 6 }}><Seg items={[['week', 'Week'], ['month', 'Month']]} value={view} onPick={setView} /></div>
          <IconButton icon="chevron-left" label="Previous" appearance="secondary" size="sm" onClick={() => step(-1)} />
          <span style={{ fontWeight: 700, color: 'var(--text-heading)', minWidth: 120, textAlign: 'center' }}>
            {isWeek ? ymdSpan(from, to) : monLabel(from.slice(0, 7))}
          </span>
          <IconButton icon="chevron-right" label="Next" appearance="secondary" size="sm" onClick={() => step(1)} />
          <span className="spacer" />
          <div className="ts-filters" role="group" aria-label="Show">
            <button type="button" className="ts-f" aria-pressed={!search.show} onClick={() => setShow(undefined)}>
              All<span className="ts-fn">{rows.length}</span>
            </button>
            {SHOWS.map((f) => (
              <button key={f.key} type="button" className={`ts-f f-${f.key}`} aria-pressed={search.show === f.key} onClick={() => setShow(f.key)}>
                {f.mark}{f.label}<span className="ts-fn">{rows.filter(f.has).length}</span>
              </button>
            ))}
          </div>
          <HowToRead />
        </div>
        {q.isPending ? <Loading /> : q.isError ? <div style={{ padding: 16 }}><QueryError error={q.error} /></div>
          : <Grid v={q.data} show={search.show} fit={isWeek || q.data.days.length <= PER_SCREEN} />}
      </section>
    </div>
  );
}

/** The legend, behind an (i): how to read a day, and what each tint says. */
function HowToRead() {
  const row = (sw: ReactNode, text: string) => <span className="ts-tip-r">{sw}<span>{text}</span></span>;
  return (
    <button type="button" className="ts-info" aria-label="How to read the timesheet">
      <Icon name="info" size={17} />
      <span className="ts-tip" role="tooltip">
        <b className="ts-tip-h">How to read a day</b>
        {row(<span className="ts-v ts-tip-v"><b className="ts-a">5.50h</b><span className="ts-p">/ 4.58h</span></span>, 'Actual logged in Jira / Plan of the day')}
        <span className="ts-tip-h2">Against the allocation of the day</span>
        {row(<span className="ts-sw"><span className="ts-mark"><span className="ts-dot ok" /></span></span>, 'On allocation')}
        {row(<span className="ts-sw st-under"><span className="ts-mark"><span className="ts-dot under" /></span></span>, 'Under allocation')}
        {row(<span className="ts-sw st-over"><span className="ts-mark ts-warn"><Icon name="triangle-alert" size={12} strokeWidth={2.4} /></span></span>, 'Over allocation: burnout risk')}
        {row(<span className="ts-sw st-no_allocation"><span className="ts-mark"><span className="ts-dot no_allocation" /></span></span>, 'No allocation: logged on an anken without allocation')}
        {row(<span className="ts-sw st-off"><span className="ts-off">Off</span></span>, 'Day off')}
        <span className="ts-tip-f">Hover a cell for worklogs · click to pin</span>
      </span>
    </button>
  );
}

/** What a day cell shows: actual (none when nothing can be logged yet) and plan; the tint and mark come from the state. */
function dayText(c: Cell): { actual: string | null; plan: string | null } {
  if (c.state === 'off') return { actual: null, plan: null };
  const counted = c.state === 'ok' || c.state === 'under' || c.state === 'over' || c.state === 'no_allocation';
  return {
    actual: counted || c.logged_h > 0 ? hrs(c.logged_h) : null,
    plan: c.planned_h ? hrs(c.planned_h) : null,
  };
}

function Mark({ state }: { state: string }) {
  if (state === 'over') return <span className="ts-mark ts-warn" aria-hidden="true"><Icon name="triangle-alert" size={12} strokeWidth={2.4} /></span>;
  if (state === 'ok' || state === 'under' || state === 'no_allocation') return <span className="ts-mark" aria-hidden="true"><span className={`ts-dot ${state}`} /></span>;
  return null;
}

function Grid({ v, show, fit }: { v: Schemas['TimesheetView']; show: Show | undefined; fit: boolean }) {
  const members = useMemberMap();
  const [hover, setHover] = useState<Pop | null>(null);
  const [pin, setPin] = useState<Pop | null>(null);
  const n = v.days.length;
  const ti = v.days.findIndex((d) => d.is_today);
  const pick = SHOWS.find((f) => f.key === show);
  const rows = pick ? v.rows.filter(pick.has) : v.rows;
  const pos = (e: MouseEvent<HTMLElement>, member: string, date: string, pinned: boolean): Pop => {
    const r = e.currentTarget.getBoundingClientRect();
    return { member, date, pinned, top: Math.max(8, Math.min(r.bottom + 6, innerHeight - 360)), left: Math.max(8, Math.min(r.left + r.width / 2 - 180, innerWidth - 376)) };
  };
  const pop = pin ?? hover;
  const cols = { gridTemplateColumns: `${MEMBER_W}px repeat(${n}, minmax(0, 1fr)) ${TOTAL_W}px` };
  return (
    <>
      <div style={{ overflowX: 'auto' }}>
        <div className={`ts-grid${n > 7 ? ' dense' : ''}`} style={{ width: fit ? '100%' : `calc((100% - ${SIDE}px) * ${(n / PER_SCREEN).toFixed(4)} + ${SIDE}px)` }}>
          {ti >= 0 && <span className="ts-frame" aria-hidden="true"
            style={{ left: `calc(${MEMBER_W}px + (100% - ${SIDE}px) * ${(ti / n).toFixed(5)} + 2px)`, width: `calc((100% - ${SIDE}px) / ${n} - 4px)` }} />}
          <div className="ts-row ts-head" style={cols}>
            <div className="sticky-col ts-mh">Member<span>each day: actual / plan</span></div>
            {v.days.map((d) => {
              const h = dayHeadStyle(d);
              return (
                <div key={d.date} className={`ts-dh${d.is_today ? ' today' : ''}`} title={d.is_today ? 'Today' : h.tip}
                  style={d.is_today ? undefined : { background: h.bg, color: h.fg }}>
                  <span className="ts-dd">{WDN[dow(d.date)]}</span>
                  <span className="ts-dn">{d.date.slice(8, 10)}</span>
                  {d.is_today ? <span className="ts-today">Today</span> : d.extra_note ? <span className="ts-xtra">Extra day</span> : null}
                </div>
              );
            })}
            <div className="ts-th">Week total<span>actual / plan</span></div>
          </div>
          {rows.map((r) => {
            const m = members[r.member_id];
            const plan = r.cells.reduce((t, c) => t + c.planned_h, 0);
            const unallocated = r.cells.filter((c) => c.state === 'no_allocation').length;
            return (
              <div key={r.member_id} className="ts-row" style={cols}>
                <div className="sticky-col ts-m">
                  <Avatar a={av(m, r.member_id)} size={28} />
                  <div className="ts-mn">
                    <div className="ts-name"><span className="ellipsis">{m?.name ?? r.member_id}</span>{m?.role && <span className="ts-role">{m.role}</span>}</div>
                    {(r.over_days > 0 || r.under_days > 0 || unallocated > 0) && (
                      <div className="ts-tags">
                        {r.over_days > 0 && <span className="ts-tag over"><Icon name="triangle-alert" size={10} strokeWidth={2.6} />{r.over_days}d over</span>}
                        {r.under_days > 0 && <span className="ts-tag under">{r.under_days}d under</span>}
                        {!r.over_days && !r.under_days && <span className="ts-tag no_allocation">{unallocated}d without allocation</span>}
                      </div>
                    )}
                  </div>
                </div>
                {r.cells.map((c, i) => {
                  const clickable = c.state !== 'future' && (c.logged_h > 0 || (c.state !== 'weekend' && c.state !== 'off'));
                  const { actual, plan: planned } = dayText(c);
                  // The state is told by colour, so screen readers hear it after what the cell shows.
                  const name = [c.state === 'off' ? 'off' : actual, planned ? `plan ${planned}` : c.state === 'weekend' || c.state === 'off' ? '' : 'plan –', STATE_LABEL[c.state]]
                    .filter(Boolean).join(' · ');
                  return (
                    <button key={c.date} type="button" className={`ts-c st-${c.state}${i === ti ? ' today' : ''}`} aria-label={name || undefined}
                      title={`${m?.name ?? r.member_id} · ${WDN[dow(c.date)]} ${dm(c.date)} · ${hrs(c.logged_h)} logged${c.target_h > 0 ? ` · target ${hrs(c.target_h)}` : ''}${c.unallocated_h > 0 ? ` · +${hrs(c.unallocated_h)} on anken without allocation` : ''}${c.off_reason ? ' · ' + c.off_reason : ''}`}
                      onClick={(e) => clickable && setPin(pos(e, r.member_id, c.date, true))}
                      onMouseEnter={(e) => clickable && !pin && setHover(pos(e, r.member_id, c.date, false))}
                      onMouseLeave={() => setHover(null)}
                      style={{ cursor: clickable ? 'pointer' : 'default' }}>
                      <Mark state={c.state} />
                      {c.state === 'off' ? <span className="ts-off">Off</span>
                        : !actual && !planned ? <span className="ts-none">{c.state === 'weekend' ? '' : '–'}</span>
                          : <span className="ts-v"><b className={`ts-a${actual ? '' : ' nil'}`}>{actual ?? '–'}</b><span className="ts-p"><span className="ts-sl">/ </span>{planned ?? '–'}</span></span>}
                    </button>
                  );
                })}
                <div className="ts-t">
                  {r.logged_h || plan ? (
                    <>
                      <span className="ts-v"><b>{hrs(r.logged_h)}</b><span>/ {hrs(plan)}</span></span>
                      <span className="ts-ta">{r.target_h ? `allocated ${hrs(r.target_h)} to date` : 'nothing allocated to date'}</span>
                    </>
                  ) : <span className="ts-none">–</span>}
                </div>
              </div>
            );
          })}
          {!rows.length && <div className="ts-empty">{v.rows.length ? 'No member matches this filter.' : 'No member in this scope.'}</div>}
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
  const { anken } = useAnkenScope();
  const q = useQuery(dayDetailQuery(pop.member, pop.date, anken));
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
          <span style={{ fontSize: 11, color: 'var(--text-muted)', textAlign: 'right', whiteSpace: 'nowrap' }}>plan {e.planned_h ? f2(e.planned_h) + 'h' : '–'}</span>
          <span style={{ fontSize: 11, color: 'var(--text-muted)', gridColumn: '1 / -1' }}>{e.notes.join(' · ') || 'No worklog yet'}</span>
        </div>
      ))}
      {d && !d.entries.length && <div style={{ padding: '12px 14px', fontSize: 12, color: 'var(--hl-red-fg)' }}>No worklogs on this working day.</div>}
    </div>
  );
}
