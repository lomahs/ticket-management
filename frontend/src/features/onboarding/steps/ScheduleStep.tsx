import { useQuery } from '@tanstack/react-query';
import { useState, type CSSProperties } from 'react';
import type { Schemas } from '../../../shared/api/types';
import { add, dayDiff, dm, dow, hrs, mondayOf, WDN } from '../../../shared/format/dates';
import { Loading, QueryError } from '../../../shared/ui/states';
import { masterScheduleQuery, schedulePreviewQuery, useSaveMasterSchedule, type PhaseIn, type PhaseRow } from '../../planning';
import { onboardingMembersQuery, type MemberHours } from '../api';
import { StepShell } from '../components/StepShell';
import { useDebounced } from '../../../shared/state/debounce';

/** Step 5: one Start date; the phases follow each other, each ending when its members have given its effort. */
export function ScheduleStep({ epic }: { epic: string }) {
  const q = useQuery(masterScheduleQuery(epic));
  const m = useQuery(onboardingMembersQuery(epic));
  if (q.isPending || m.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  if (m.isError) return <QueryError error={m.error} />;
  return <Schedule key={q.data.current?.id ?? 0} epic={epic} v={q.data} people={m.data.filter((x) => x.in_anken)} />;
}


const COLORS = ['var(--hl-blue-fg, #2F6FD6)', 'var(--hl-purple-fg, #6A4FD8)', 'var(--hl-green-fg, #2E7D4F)', 'var(--hl-amber-fg, #B26A00)', 'var(--hl-red-fg, #B3261E)'];
const COLS = 'minmax(120px,1.2fr) 80px minmax(220px,3fr) 70px 70px 70px';
const INPUT: CSSProperties = { height: 32, boxSizing: 'border-box', padding: '0 8px', border: '1px solid var(--border-strong)', borderRadius: 8, background: 'var(--surface-card)', color: 'var(--text-heading)', font: '500 13px/1 var(--font-text)' };

function Schedule({ epic, v, people }: { epic: string; v: Schemas['MasterScheduleView']; people: MemberHours[] }) {
  const save = useSaveMasterSchedule(epic);
  // the default team of a phase: the members who give the anken hours, and its JMs (their reviews take no allocation)
  const team = people.filter((p) => p.role === 'JM' || (p.hours_per_day ?? 0) > 0).map((p) => p.member_id);
  const [start, setStart] = useState(v.current?.phases[0]?.start ?? '');
  const [who, setWho] = useState<Record<string, string[]>>(() => Object.fromEntries(v.estimation_phases.map((p) =>
    [p, v.current?.phases.find((x) => x.phase === p)?.member_ids ?? team])));
  const [editing, setEditing] = useState<string | null>(null);
  const [reason, setReason] = useState(v.current ? '' : 'Kick-off');
  const phases: PhaseIn[] = v.estimation_phases.map((p, i) => ({ phase: p, start: i === 0 ? start : null, member_ids: who[p] ?? [] }));
  const complete = !!start && phases.every((p) => p.member_ids.length > 0);
  const pv = useQuery(schedulePreviewQuery(epic, useDebounced(complete ? phases : [], 250)));
  const rows = complete ? pv.data ?? [] : [];
  // unchanged since the saved version: same Start, same phases and members (judged on the inputs, not on the preview)
  const same = !!v.current && v.current.phases.length === phases.length && v.current.phases[0]?.start === start
    && phases.every((p, i) => p.phase === v.current!.phases[i].phase && p.member_ids.join() === v.current!.phases[i].member_ids.join());
  const name = (id: string) => people.find((p) => p.member_id === id)?.name ?? id;
  const end = rows.length ? rows[rows.length - 1].end : null;
  return (
    <StepShell epic={epic} step="schedule" title="Master schedule" sub="Enter the start date: the dates of every phase and the Gantt follow."
      canContinue={!!v.current || complete}
      onSave={async () => {
        if (!complete) {
          if (v.current) return;
          throw new Error('Enter the start date and the members of every phase');
        }
        if (same) return;
        if (!reason.trim()) throw new Error('Say why the master schedule changes');
        await save.mutateAsync({ phases, reason: reason.trim() });
        setReason('');
      }}>
      <section className="card" style={{ padding: '14px 18px', display: 'flex', flexWrap: 'wrap', alignItems: 'flex-end', gap: 24 }}>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 6, fontWeight: 600 }}>Start date
          <input type="date" value={start} onChange={(e) => setStart(e.target.value)} style={INPUT} />
        </label>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}><span className="eyebrow">Anken ends</span><b className="heading" style={{ fontSize: 18 }}>{end ? dm(end) : '–'}</b></div>
        {v.current && <span className="eyebrow">{`Saved version: ${v.current.reason}`}</span>}
      </section>

      <section className="card card-clip" aria-label="Master schedule phases">
        <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)', display: 'flex', flexDirection: 'column', gap: 2 }}>
          <span className="title">Phases</span>
          <span className="eyebrow">BA effort comes from the estimation: Create, Fix BAL and Fix JM, and tickets (BAL and JM reviews aside). The End is the later of the day the team's members other than BALs and JMs have given that effort, and the day the longest function's reviews end after the Creates. Hours per day come from Add member. Each phase starts the working day after the previous one ends.</span>
        </div>
        {v.estimation_phases.length === 0 && <div style={{ padding: 16, color: 'var(--text-muted)' }}>The estimation has no rows yet.</div>}
        <div style={{ overflowX: 'auto' }}>
          <div style={{ minWidth: 760 }}>
            <div style={{ display: 'grid', gridTemplateColumns: COLS, gap: 10, padding: '8px 16px', fontSize: 11, color: 'var(--text-muted)' }}>
              <span>Phase</span><span>BA effort</span><span>Members</span><span>Plan MD</span><span>Start</span><span>End</span>
            </div>
            {v.estimation_phases.map((p, i) => {
              const c = rows.find((r) => r.phase === p);
              const ids = who[p] ?? [];
              return (
                <div key={p} style={{ display: 'grid', gridTemplateColumns: COLS, gap: 10, alignItems: 'center', padding: '8px 16px', borderTop: '1px solid var(--border-subtle)' }}>
                  <b className="heading" style={{ display: 'flex', alignItems: 'center', gap: 8 }}><span aria-hidden="true" style={{ width: 10, height: 10, borderRadius: 3, background: COLORS[i % COLORS.length] }} />{p}</b>
                  <span>{c ? hrs(c.total_effort_h) : '–'}</span>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 10px', alignItems: 'center' }}>
                    {editing === p ? people.map((m) => (
                      <label key={m.member_id} style={{ display: 'inline-flex', gap: 4, alignItems: 'center', fontSize: 12 }}>
                        <input type="checkbox" aria-label={`${p}: ${m.name}`} checked={ids.includes(m.member_id)}
                          onChange={(e) => setWho((w) => ({ ...w, [p]: e.target.checked ? [...ids, m.member_id] : ids.filter((x) => x !== m.member_id) }))} />
                        {m.name}
                      </label>
                    )) : ids.map((id) => <span key={id} style={{ fontSize: 12 }}>{`${name(id)} · ${hrs(people.find((x) => x.member_id === id)?.hours_per_day ?? 0)}`}</span>)}
                    <button type="button" className="link" aria-label={`Edit members of ${p}`} onClick={() => setEditing(editing === p ? null : p)}
                      style={{ border: 0, background: 'transparent', color: 'var(--text-link)', cursor: 'pointer', fontSize: 12, fontWeight: 600 }}>{editing === p ? 'Done' : 'Edit'}</button>
                  </div>
                  <span>{c ? c.plan_md : '–'}</span>
                  <span>{c ? dm(c.start) : '–'}</span>
                  <span>{c ? dm(c.end) : '–'}</span>
                </div>
              );
            })}
          </div>
        </div>
        {pv.isError && complete && <div style={{ padding: '0 16px 12px' }}><QueryError error={pv.error} /></div>}
      </section>

      {rows.length > 0 && <Gantt rows={rows} />}

      <section className="card" style={{ padding: '12px 16px', display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 10 }}>
        <label htmlFor="reason" style={{ fontWeight: 600 }}>Reason for this version</label>
        <input id="reason" value={reason} onChange={(e) => setReason(e.target.value)} placeholder={v.current ? 'Why does the schedule change?' : 'e.g. Kick-off'} style={{ ...INPUT, flex: 1, minWidth: 200 }} />
        <span className="eyebrow">{v.current ? (same ? 'No change since the saved version.' : 'Saving makes a new version.') : 'The first saved version is the baseline.'}</span>
      </section>
    </StepShell>
  );
}

const DAY_W = 26;
const LABEL_W = 140;

/** The phases as bars over one column per day, Saturdays and Sundays shaded, from the start's Monday to the last end's Sunday. */
function Gantt({ rows }: { rows: PhaseRow[] }) {
  const from = mondayOf(rows[0].start);
  const last = rows.reduce((a, r) => (r.end > a ? r.end : a), rows[0].end);
  const n = dayDiff(from, add(mondayOf(last), 6)) + 1;
  const dates = Array.from({ length: n }, (_, i) => add(from, i));
  const weekend = (d: string) => dow(d) === 0 || dow(d) === 6;
  const cols = `${LABEL_W}px repeat(${n},${DAY_W}px)`;
  const label: CSSProperties = { position: 'sticky', left: 0, zIndex: 2, background: 'var(--surface-card)', paddingRight: 8 };
  const cell = (d: string, i: number): CSSProperties => ({
    gridRow: 1, gridColumn: i + 2, alignSelf: 'stretch', borderLeft: `1px solid ${dow(d) === 1 ? 'var(--border-strong)' : 'var(--border-subtle)'}`,
    background: weekend(d) ? 'var(--surface-hover)' : undefined,
  });
  return (
    <section className="card card-clip" aria-label="Gantt by phase">
      <div style={{ padding: '12px 16px', display: 'flex', gap: 10, alignItems: 'baseline' }}><span className="title">Gantt by phase</span><span className="eyebrow">One column per day; weekends shaded</span></div>
      <div style={{ overflowX: 'auto', padding: '0 16px 14px' }}>
        <div style={{ width: LABEL_W + n * DAY_W }}>
          <div role="row" aria-label="Weeks" style={{ display: 'grid', gridTemplateColumns: cols, fontSize: 11, color: 'var(--text-muted)' }}>
            <span style={label} />
            {dates.map((d, i) => dow(d) === 1 && (
              <span key={d} style={{ gridColumn: `${i + 2} / span 7`, borderLeft: '1px solid var(--border-strong)', paddingLeft: 4, fontWeight: 600 }}>{dm(d)}</span>
            ))}
          </div>
          <div role="row" aria-label="Days" style={{ display: 'grid', gridTemplateColumns: cols, fontSize: 10, color: 'var(--text-muted)', textAlign: 'center' }}>
            <span style={{ ...label, gridRow: 1, gridColumn: 1 }} />
            {dates.map((d, i) => (
              <span key={d} title={`${WDN[dow(d)]} ${dm(d)}`} data-weekend={weekend(d) || undefined}
                style={{ ...cell(d, i), padding: '2px 0', lineHeight: 1.3, color: weekend(d) ? 'var(--text-disabled)' : undefined }}>
                <div>{WDN[dow(d)][0]}</div><div>{d.slice(8, 10)}</div>
              </span>
            ))}
          </div>
          {rows.map((r, k) => (
            <div key={r.phase} role="row" aria-label={r.phase} style={{ display: 'grid', gridTemplateColumns: cols, alignItems: 'center', height: 34 }}>
              <b className="heading ellipsis" style={{ ...label, gridRow: 1, gridColumn: 1, fontSize: 12, alignSelf: 'center' }}>{r.phase}</b>
              {dates.map((d, i) => <span key={d} style={cell(d, i)} />)}
              <span title={`${r.phase} ${dm(r.start)} – ${dm(r.end)}`} style={{
                gridRow: 1, gridColumn: `${dayDiff(from, r.start) + 2} / span ${dayDiff(r.start, r.end) + 1}`, zIndex: 1, height: 22, margin: '0 1px',
                background: COLORS[k % COLORS.length], borderRadius: 6, color: '#fff', font: '600 11px/22px var(--font-text)', padding: '0 6px', overflow: 'hidden', whiteSpace: 'nowrap',
              }}>{`${dm(r.start)} – ${dm(r.end)}`}</span>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
