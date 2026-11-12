import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { errorMessage } from '../../../shared/api/errors';
import { useCan, useMemberMap } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button, Icon, Select } from '../../../shared/ds';
import { D, dm, f2, WDN } from '../../../shared/format/dates';
import { signedH } from '../../../shared/format/labels';
import { useAnkenScope } from '../../../shared/state/anken';
import { useToast } from '../../../shared/state/toast';
import { BurndownChart } from '../../../shared/ui/BurndownChart';
import { PickAnken } from '../../../shared/ui/PickAnken';
import { Loading, QueryError } from '../../../shared/ui/states';
import { dailyQuery, useCapture } from '../api';
import { FunctionOverview } from './FunctionOverview';
import { Issues } from './Issues';
import { C } from './metro';
import { Process } from './Process';

const route = getRouteApi('/app/daily');
const ALL = '';

/** "Mon 06/10". */
const day = (d: string) => `${WDN[D(d).getUTCDay()]} ${dm(d)}`;
/** "18:00" of an ISO local date-time. */
const hm = (at: string) => at.slice(11, 16);

export function DailyPage() {
  const { anken } = useAnkenScope();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20, width: '100%', minWidth: 0 }}>
      {anken ? <DailyOf anken={anken} /> : (
        <>
          <h1 className="h1" style={{ margin: 0 }}>Daily Overview</h1>
          <PickAnken />
        </>
      )}
    </div>
  );
}

/**
 * One anken: today live, or a captured day (`date`). The phase picked in Process (`phase`, none: all phases) sets what the
 * header, the burndown and the Function overview show.
 */
function DailyOf({ anken }: { anken: string }) {
  const { date, phase } = route.useSearch();
  const navigate = route.useNavigate();
  const q = useQuery(dailyQuery(anken, date));
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  const v = q.data;
  const c = v.content;
  const daily = v.daily_phases;
  // the anken's Daily phases (Settings → Structure), else every phase group, in the server's order
  const groups = daily.length ? c.groups.filter((g) => daily.includes(g.name)) : c.groups;
  const none = groups.length ? null
    : !daily.length ? 'No phase has started yet.'
      : v.live ? 'No phase to show. Pick the phases in Settings → Structure.' : 'None of the Daily phases had started on this day.';
  const picked = phase != null && groups.some((g) => g.name === phase) ? phase : undefined;
  const all = c.burndowns.find((b) => b.phase == null) ?? c.burndowns[0];
  const chart = picked == null ? all : c.burndowns.find((b) => b.phase === picked);
  return (
    <>
      <Header v={v} b={chart ?? all} />
      <Toolbar v={v} anken={anken} pick={(d) => navigate({ search: (s) => ({ ...s, date: d || undefined }) })} />
      <Process groups={groups} burndowns={c.burndowns} pct={c.pct} phase={picked} pick={(p) => navigate({ search: (s) => ({ ...s, phase: p }) })} />
      <section className="card" aria-label="Effort burndown" style={{ padding: '18px 20px', display: 'flex', flexDirection: 'column', gap: 14, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <span style={{ font: '700 18px/1.2 var(--font-display)', color: 'var(--text-heading)' }}>Effort burndown</span>
          <span className="do-pill" style={{ background: 'var(--do-wip-bg)', color: 'var(--do-wip)', fontWeight: 700, fontSize: 12 }}>
            {picked ?? 'All phases'}<span style={{ fontWeight: 500, opacity: 0.8 }}>· chosen in Process</span>
          </span>
          <span className="spacer" />
          <div style={{ display: 'flex', gap: 16, fontSize: 13, color: 'var(--text-muted)' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}><span style={{ width: 20, height: 0, borderTop: '2px dashed var(--text-muted)' }} />Plan</span>
            <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}><span style={{ width: 20, height: 3, borderRadius: 2, background: 'var(--st-wip)' }} />Remaining</span>
          </div>
        </div>
        {chart ? (
          <BurndownChart b={chart.chart} today={c.date} todayLabel={v.live ? 'Today' : day(c.date)} height={260}
            empty="No sub-tasks with a Planned Start, a Planned End and an estimate." />
        ) : (
          <div style={{ padding: '40px 0', textAlign: 'center', fontSize: 13, color: 'var(--text-muted)' }}>
            <b style={{ color: 'var(--text-heading)' }}>{picked}</b> has not started: no sub-tasks with a plan and an estimate yet.
          </div>
        )}
      </section>
      <FunctionOverview groups={groups} phase={picked} none={none} />
      <Issues typed={c.typed} anken={anken} editable={v.live} />
    </>
  );
}

/** A snapshot captured before the burndowns kept their progress has it for the whole anken only. */
function Header({ v, b }: { v: Schemas['DailyView']; b: Schemas['DailyBurndown'] }) {
  const c = v.content;
  const p = b.progress ?? (b.phase == null ? c : null);
  const of = b.phase ? ` · ${b.phase}` : '';
  const kpi = { display: 'grid', gridTemplateRows: '16px 44px 16px', gap: 6, alignContent: 'center' } as const;
  return (
    <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: 18, padding: '20px 24px', borderRadius: 14 }}>
      <div style={{ minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, lineHeight: '16px', color: 'var(--text-muted)' }}>
          <span style={{ fontWeight: 600 }}>Daily Overview</span><span>·</span><span style={{ fontFamily: 'var(--font-mono)' }}>{c.epic}</span>
        </div>
        <h1 style={{ margin: '6px 0 0', font: '700 34px/44px var(--font-display)', color: 'var(--text-heading)', textWrap: 'pretty' }}>{c.title}</h1>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,220px) minmax(0,320px)', paddingTop: 18, borderTop: '1px solid var(--border-subtle)' }}>
        <div style={{ ...kpi, paddingRight: 24 }}>
          <span style={{ fontSize: 13, color: 'var(--text-muted)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{b.phase ? 'Progress' : 'Overall progress'}{of}</span>
          <span style={{ font: '700 40px/44px var(--font-display)', color: 'var(--text-heading)' }}>{p ? `${p.pct}%` : '–'}</span>
          <div style={{ alignSelf: 'center', height: 10, borderRadius: 99, background: 'var(--border-subtle)', overflow: 'hidden' }}>
            <div style={{ height: 10, width: (p?.pct ?? 0) + '%', background: 'var(--text-heading)', borderRadius: 99 }} />
          </div>
        </div>
        <div style={{ ...kpi, padding: '0 24px', borderLeft: '1px solid var(--border-subtle)' }}>
          <span style={{ fontSize: 13, color: 'var(--text-muted)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>Effort vs plan{of}</span>
          <span style={{ font: '700 40px/44px var(--font-display)', color: !p ? 'var(--text-heading)' : p.diff_h <= 0 ? C.done : C.late }}>{p ? signedH(p.diff_h) : '–'}</span>
          {p ? (
            <span style={{ fontSize: 12, color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
              Plan remain <b style={{ fontFamily: 'var(--font-mono)', color: 'var(--text-heading)' }}>{f2(p.plan_remain_h)}h</b>
              <span aria-hidden="true" style={{ margin: '0 8px', color: 'var(--border-strong)' }}>|</span>
              Actual remain <b style={{ fontFamily: 'var(--font-mono)', color: 'var(--text-heading)' }}>{f2(p.actual_remain_h)}h</b>
            </span>
          ) : <span style={{ fontSize: 12, color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>not captured for this phase</span>}
        </div>
      </div>
    </div>
  );
}

/** The day shown (today live, or a captured day), when it was captured, and the Capture button for today. */
function Toolbar({ v, anken, pick }: { v: Schemas['DailyView']; anken: string; pick: (date: string) => void }) {
  const members = useMemberMap();
  const canCapture = useCan('overview', 'write');
  const capture = useCapture(anken);
  const toast = useToast();
  const by = v.captured_by === 'auto' ? 'auto' : members[v.captured_by ?? '']?.name ?? v.captured_by;
  const captured = v.captured_at ? `captured ${v.live ? '' : day(v.captured_at.slice(0, 10)) + ' '}${hm(v.captured_at)} · ${by}` : 'not captured yet';
  const options = [
    { value: ALL, label: `Today · ${day(v.today)} (live)` },
    ...v.snapshots.filter((s) => s.date !== v.today).map((s) => ({ value: s.date, label: `${day(s.date)} · ${hm(s.captured_at)}` })),
  ];
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', paddingBottom: 4, borderBottom: '1px solid var(--border-subtle)' }}>
      <Icon name="camera" size={14} color="var(--text-muted)" />
      <Select aria-label="Day" style={{ width: 240 }} value={v.live ? ALL : v.content.date} options={options} onChange={pick} />
      {!v.live && (
        <span style={{ padding: '2px 8px', borderRadius: 99, background: 'var(--surface-tint)', color: 'var(--text-heading)', font: '600 11px/1.4 var(--font-text)' }}>
          Past snapshot
        </span>
      )}
      <span className="spacer" />
      <span className="do-pill" style={{ height: 26, background: v.live ? 'var(--do-wip-bg)' : 'var(--surface-hover)', color: v.live ? 'var(--do-wip)' : 'var(--text-body)', fontWeight: 600, fontSize: 12 }}>
        <span aria-hidden="true" style={{ width: 7, height: 7, borderRadius: '50%', background: 'currentColor' }} />{v.live ? 'Live' : 'Snapshot'} · {captured}
      </span>
      <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{v.auto_capture === 'off' ? 'Auto capture off' : `Auto capture daily at ${v.auto_capture}`}</span>
      {v.live && canCapture && (
        <Button appearance="secondary" size="sm" iconBefore="camera" disabled={capture.isPending}
          onClick={() => capture.mutate(undefined, {
            onSuccess: () => toast(`Captured the Daily Overview of ${day(v.today)}`),
            onError: (e) => toast(errorMessage(e), true),
          })}>
          Capture
        </Button>
      )}
    </div>
  );
}
