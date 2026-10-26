import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { errorMessage } from '../../../shared/api/errors';
import { useCan, useMemberMap } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button, Icon, Select } from '../../../shared/ds';
import { D, dm, f1, WDN } from '../../../shared/format/dates';
import { signedH } from '../../../shared/format/labels';
import { useAnkenScope } from '../../../shared/state/anken';
import { useToast } from '../../../shared/state/toast';
import { BurndownChart } from '../../../shared/ui/BurndownChart';
import { PickAnken } from '../../../shared/ui/PickAnken';
import { Loading, QueryError } from '../../../shared/ui/states';
import { dailyQuery, useCapture } from '../api';
import { PhaseTable } from './PhaseTable';
import { TypedSection } from './TypedSection';

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

/** One anken: today live, or a captured day (`date`); the burndown's phase is in the URL too. */
function DailyOf({ anken }: { anken: string }) {
  const { date, phase } = route.useSearch();
  const navigate = route.useNavigate();
  const q = useQuery(dailyQuery(anken, date));
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  const v = q.data;
  const c = v.content;
  const chart = c.burndowns.find((b) => (b.phase ?? ALL) === (phase ?? ALL)) ?? c.burndowns[0];
  return (
    <>
      <Header v={v} />
      <Toolbar v={v} anken={anken} pick={(d) => navigate({ search: (s) => ({ ...s, date: d || undefined }) })} />
      <section className="card" aria-label="Effort burndown" style={{ padding: '18px 20px', display: 'flex', flexDirection: 'column', gap: 14, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <span style={{ font: '700 18px/1.2 var(--font-display)', color: 'var(--text-heading)' }}>Effort burndown</span>
          <span className="spacer" />
          <div style={{ display: 'flex', gap: 16, fontSize: 13, color: 'var(--text-muted)' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}><span style={{ width: 20, height: 0, borderTop: '2px dashed var(--text-muted)' }} />Plan</span>
            <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}><span style={{ width: 20, height: 3, borderRadius: 2, background: 'var(--st-wip)' }} />Remaining</span>
          </div>
          <Select
            aria-label="Phase"
            style={{ width: 200 }}
            value={chart.phase ?? ALL}
            options={c.burndowns.map((b) => ({ value: b.phase ?? ALL, label: b.phase ?? 'All phases' }))}
            onChange={(p) => navigate({ search: (s) => ({ ...s, phase: p || undefined }) })}
          />
        </div>
        <BurndownChart b={chart.chart} today={c.date} todayLabel={v.live ? 'Today' : day(c.date)} height={260}
          empty="No sub-tasks with a Planned Start, a Planned End and an estimate." />
      </section>
      <PhaseTable groups={c.groups} />
      <TypedSection typed={c.typed} anken={anken} editable={v.live} />
    </>
  );
}

function Header({ v }: { v: Schemas['DailyView'] }) {
  const c = v.content;
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
          <span style={{ fontSize: 13, color: 'var(--text-muted)' }}>Overall progress</span>
          <span style={{ font: '700 40px/44px var(--font-display)', color: 'var(--text-heading)' }}>{c.pct}%</span>
          <div style={{ alignSelf: 'center', height: 6, borderRadius: 99, background: 'var(--border-subtle)', overflow: 'hidden' }}>
            <div style={{ height: 6, width: c.pct + '%', background: 'var(--text-heading)' }} />
          </div>
        </div>
        <div style={{ ...kpi, padding: '0 24px', borderLeft: '1px solid var(--border-subtle)' }}>
          <span style={{ fontSize: 13, color: 'var(--text-muted)' }}>Effort vs plan</span>
          <span style={{ font: '700 40px/44px var(--font-display)', color: 'var(--text-heading)' }}>{signedH(c.diff_h)}</span>
          <span style={{ fontSize: 12, color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
            plan to date {f1(c.plan_to_date_h)}h · actual {f1(c.actual_to_date_h)}h
          </span>
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
      <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{v.live ? 'Live' : 'Snapshot'} · {captured}</span>
      {!v.live && (
        <span style={{ padding: '2px 8px', borderRadius: 99, background: 'var(--surface-tint)', color: 'var(--text-heading)', font: '600 11px/1.4 var(--font-text)' }}>
          Past snapshot
        </span>
      )}
      <span className="spacer" />
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
