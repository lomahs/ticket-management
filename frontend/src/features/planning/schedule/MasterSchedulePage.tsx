import { useQuery } from '@tanstack/react-query';
import { useEffect, useState, type CSSProperties } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useCan, useMembers } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button } from '../../../shared/ds';
import { dm, hrs } from '../../../shared/format/dates';
import { useToast } from '../../../shared/state/toast';
import { PageHead } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { PlanningTabs, WithAnken } from '../components/PlanningTabs';
import { masterScheduleQuery, schedulePreviewQuery, useSaveMasterSchedule, type PhaseIn } from './api';

/** Step 2 of planning: start and members of each phase; effort, plan MD and end follow; each save is a version. */
export function MasterSchedulePage() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, width: '100%' }}>
      <PageHead eyebrow="Planning · step 2" title="Master schedule" />
      <PlanningTabs at="/planning/schedule" />
      <WithAnken>{(epic) => <Schedule key={epic} epic={epic} />}</WithAnken>
    </div>
  );
}

function Schedule({ epic }: { epic: string }) {
  const q = useQuery(masterScheduleQuery(epic));
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  return <Editor key={q.data.current?.id ?? 0} epic={epic} v={q.data} />;
}

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => { const t = setTimeout(() => setV(value), ms); return () => clearTimeout(t); }, [value, ms]);
  return v;
}

const COLS = '90px 90px minmax(200px,3fr) 150px 70px 90px';
const DATE: CSSProperties = { height: 30, boxSizing: 'border-box', padding: '0 8px', border: '1px solid var(--border-strong)', borderRadius: 6, background: 'var(--surface-card)', color: 'var(--text-heading)', font: '500 13px/1 var(--font-text)' };
const ago = (n: number) => (n === 0 ? 'on time' : `${Math.abs(n)} day${Math.abs(n) === 1 ? '' : 's'} ${n > 0 ? 'later' : 'earlier'}`);

function Editor({ epic, v }: { epic: string; v: Schemas['MasterScheduleView'] }) {
  const admin = useCan('planning', 'write');
  const toast = useToast();
  const members = useMembers().data ?? [];
  const save = useSaveMasterSchedule(epic);
  const [rows, setRows] = useState<PhaseIn[]>(v.estimation_phases.map((p) => {
    const c = v.current?.phases.find((x) => x.phase === p);
    return { phase: p, start: c?.start ?? '', member_ids: c?.member_ids ?? [] };
  }));
  const [reason, setReason] = useState('');
  const complete = rows.filter((r) => r.start && r.member_ids.length);
  const body = useDebounced(complete, 250);
  const pv = useQuery(schedulePreviewQuery(epic, body));
  const computed = (p: string) => pv.data?.find((x) => x.phase === p);
  const edit = (i: number, p: Partial<PhaseIn>) => setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...p } : r)));
  return (
    <>
      <section className="card card-clip" aria-label="Master schedule phases">
        <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)', display: 'flex', flexDirection: 'column', gap: 2 }}>
          <span className="title">Phases</span>
          <span className="eyebrow">Effort comes from the estimation. Each day the members give their allocation on this anken, without days off, until the effort is used: that day is the End.</span>
        </div>
        {v.estimation_phases.length === 0 && <div style={{ padding: 16, color: 'var(--text-muted)' }}>The estimation has no rows yet.</div>}
        <div style={{ overflowX: 'auto' }}>
          <div style={{ minWidth: 760 }}>
            <div style={{ display: 'grid', gridTemplateColumns: COLS, gap: 10, padding: '8px 16px', fontSize: 11, color: 'var(--text-muted)' }}>
              <span>Phase</span><span>Effort</span><span>Members</span><span>Start</span><span>Plan MD</span><span>End</span>
            </div>
            {rows.map((r, i) => {
              const c = computed(r.phase);
              return (
                <div key={r.phase} style={{ display: 'grid', gridTemplateColumns: COLS, gap: 10, alignItems: 'center', padding: '6px 16px', borderTop: '1px solid var(--border-subtle)' }}>
                  <b className="heading">{r.phase}</b>
                  <span>{c ? hrs(c.total_effort_h) : '–'}</span>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 12px' }}>
                    {members.map((m) => (
                      <label key={m.id} style={{ display: 'inline-flex', gap: 4, alignItems: 'center', fontSize: 12 }}>
                        <input type="checkbox" aria-label={`${r.phase}: ${m.name}`} disabled={!admin} checked={r.member_ids.includes(m.id)}
                          onChange={(e) => edit(i, { member_ids: e.target.checked ? [...r.member_ids, m.id] : r.member_ids.filter((x) => x !== m.id) })} />
                        {m.name}
                      </label>
                    ))}
                  </div>
                  <input type="date" aria-label={`Start, ${r.phase}`} disabled={!admin} value={r.start} onChange={(e) => edit(i, { start: e.target.value })} style={DATE} />
                  <span>{c ? c.plan_md : '–'}</span>
                  <span>{c ? dm(c.end) : '–'}</span>
                </div>
              );
            })}
          </div>
        </div>
        {pv.isError && <div style={{ padding: '0 16px 12px' }}><QueryError error={pv.error} /></div>}
        {admin && (
          <div style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '12px 16px', borderTop: '1px solid var(--border-subtle)' }}>
            <input aria-label="Reason for this version" placeholder={v.current ? 'Why does the schedule change?' : 'e.g. Kick-off'} value={reason}
              onChange={(e) => setReason(e.target.value)} style={{ ...DATE, flex: 1 }} />
            <Button disabled={!reason.trim() || !complete.length || save.isPending} onClick={() => save.mutate({ phases: complete, reason }, {
              onSuccess: () => { toast('Master schedule saved'); setReason(''); }, onError: (e) => toast(errorMessage(e), true),
            })}>Save version</Button>
          </div>
        )}
      </section>

      {v.shifts.length > 0 && (
        <section className="card card-clip" aria-label="Changes since the baseline">
          <div style={{ padding: '12px 16px' }}><span className="title">Changes since the baseline</span></div>
          {v.shifts.map((s) => (
            <div key={s.phase} style={{ display: 'grid', gridTemplateColumns: '90px 1fr 1fr 1fr', gap: 10, padding: '8px 16px', borderTop: '1px solid var(--border-subtle)', fontSize: 13 }}>
              <b className="heading">{s.phase}</b>
              <span className="muted">{s.baseline_start ? `Baseline ${dm(s.baseline_start)} – ${dm(s.baseline_end)}` : 'Not in the baseline'}</span>
              <span>{`Now ${dm(s.start)} – ${dm(s.end)}`}</span>
              <span style={{ color: s.end_days > 0 ? 'var(--hl-red-fg)' : 'var(--text-heading)', fontWeight: 600 }}>{s.baseline_start ? ago(s.end_days) : ''}</span>
            </div>
          ))}
        </section>
      )}

      {v.versions.length > 0 && (
        <section className="card card-clip" aria-label="Versions">
          <div style={{ padding: '12px 16px' }}><span className="title">Versions</span></div>
          {[...v.versions].reverse().map((x) => (
            <div key={x.id} style={{ display: 'flex', gap: 12, padding: '8px 16px', borderTop: '1px solid var(--border-subtle)', fontSize: 13 }}>
              <span className="muted">{x.saved_at.slice(0, 10)}</span>
              <span>{x.reason}</span>
              <span className="muted">{x.saved_by}</span>
              {x.id === v.versions[0].id && <span className="eyebrow">Baseline</span>}
            </div>
          ))}
        </section>
      )}
    </>
  );
}
