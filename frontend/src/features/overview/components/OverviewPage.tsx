import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useNavigate, useParams } from '@tanstack/react-router';
import type { Schemas } from '../../../shared/api/types';
import { useCan, useMe, useMemberMap } from '../../../shared/api/queries';
import { Icon, type IconName } from '../../../shared/ds';
import { dm } from '../../../shared/format/dates';
import { statusOf } from '../../../shared/format/labels';
import { taskShort } from '../../../shared/format/task';
import { useAnkenScope } from '../../../shared/state/anken';
import { useOpenTicket } from '../../../shared/state/ticket';
import { Avatar, av } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { ankenDetailQuery, overviewQuery } from '../api';
import { MembersPanel, NotMembers } from './MembersPanel';
import { PhaseSchedule, ScheduleSummary } from './ScheduleSummary';

const ATTN: Record<string, { label: string; icon: IconName; meta: (c: Schemas['TaskCard']) => string }> = {
  overdue: { label: 'Overdue', icon: 'alarm-clock', meta: (c) => `late ${c.late_days}d` },
  stuck_resolved: { label: 'Stuck in Resolved', icon: 'pause-circle', meta: (c) => `${c.held_days} days` },
  many_rounds: { label: 'Many review rounds', icon: 'refresh-cw', meta: (c) => `round ${c.rounds}` },
  waiting_client: { label: 'Waiting long on client (JM)', icon: 'hourglass', meta: (c) => `${c.held_days} days` },
};

export function OverviewPage() {
  const params = useParams({ strict: false }) as { epic?: string };
  const q = useQuery(overviewQuery(useAnkenScope().anken));
  const projects = [...new Set((q.data ?? []).map((a) => a.project))].join(', ');
  const selected = params.epic ?? q.data?.[0]?.key;
  // Whoever sees every anken sees none only when none is tracked (only the admin can add them); anyone else is in none yet.
  const me = useMe().data;
  const empty = me?.admin
    ? <>No epics are tracked yet. Add them in <Link to="/settings/$tab" params={{ tab: 'jira' }}>Settings → Jira</Link>.</>
    : me?.all_anken ? 'No epics are tracked yet.' : 'You are not a member of any anken yet.';
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18, width: '100%' }}>
      <div>
        <div className="eyebrow">{q.data ? `Project ${projects} · ${q.data.length} active anken` : ' '}</div>
        <h1 className="h1" style={{ marginTop: 2 }}>Anken Overview</h1>
      </div>
      {q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : !q.data.length ? (
        <div className="card" style={{ padding: 24, color: 'var(--text-muted)' }}>{empty}</div>
      ) : (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(290px,1fr))', gap: 14 }}>
            {q.data.map((a) => <AnkenCardView key={a.key} a={a} on={a.key === selected} />)}
          </div>
          {selected && <Detail epic={selected} />}
        </>
      )}
    </div>
  );
}

function AnkenCardView({ a, on }: { a: Schemas['AnkenCard']; on: boolean }) {
  const navigate = useNavigate();
  const members = useMemberMap();
  const pick = () => navigate({ to: '/anken/$epic', params: { epic: a.key }, search: (p) => p });
  const r = a.estimate_h ? a.used_h / a.estimate_h : 0;
  const slipped = !!(a.plan && a.forecast && a.forecast > a.plan);
  return (
    <div className="hv-bd" role="button" tabIndex={0} aria-pressed={on} onClick={pick} onKeyDown={(e) => e.key === 'Enter' && pick()} style={{
      display: 'flex', flexDirection: 'column', gap: 12, padding: '14px 16px', borderRadius: 14, background: 'var(--surface-card)', cursor: 'pointer',
      border: `1px solid ${on ? 'var(--border-focus)' : 'var(--border-subtle)'}`, boxShadow: on ? '0 0 0 1px var(--border-focus)' : 'none',
    }}>
      <div>
        <div style={{ font: '600 11px/1 var(--font-mono)', color: 'var(--text-muted)' }}>{a.key}</div>
        <div style={{ marginTop: 4, font: '700 15px/1.3 var(--font-display)', color: 'var(--text-heading)' }}>{a.title}</div>
        {a.pim_ids.length > 0 && (
          <div style={{ marginTop: 2, fontSize: 12, color: 'var(--text-muted)' }}>PIM: {a.pim_ids.map((id) => members[id]?.name ?? id).join(', ')}</div>
        )}
      </div>
      <div>
        <div style={{ display: 'flex', gap: 3, height: 8 }}>
          {a.phases.map((p) => (
            <div key={p.name} title={`${p.name} ${p.pct}%`} style={{ flex: 1, borderRadius: 99, background: 'var(--surface-tint)', overflow: 'hidden' }}>
              <div style={{ height: '100%', width: p.pct + '%', background: 'var(--surface-accent)' }} />
            </div>
          ))}
        </div>
      </div>
      <ScheduleSummary epic={a.key} s={a.schedule} />
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px 12px', fontSize: 12 }}>
        <div>
          <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>Plan → Forecast</div>
          <div style={{ fontWeight: 600, color: 'var(--text-heading)' }}>
            {dm(a.plan)} → <span style={{ color: slipped ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{dm(a.forecast)}{slipped ? ` (+${a.slip_days}d)` : ''}</span>
          </div>
        </div>
        <div>
          <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{a.has_functions ? 'Late functions' : 'Late sub-tasks'}</div>
          {(() => {
            const n = a.has_functions ? a.late_functions : a.late_subtasks;
            return <div style={{ fontWeight: 600, color: n ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{n ? `${n} ${a.has_functions ? 'function' : 'sub-task'}` : 'None'}</div>;
          })()}
        </div>
        <div style={{ gridColumn: '1 / -1' }}>
          <div style={{ display: 'flex', fontSize: 11, color: 'var(--text-muted)' }}>
            <span style={{ flex: 1 }}>Effort used / estimate</span>
            <span style={{ fontWeight: 600, color: r > 1 ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{Math.round(a.used_h)} / {Math.round(a.estimate_h)}h</span>
          </div>
          <div style={{ height: 4, borderRadius: 99, background: 'var(--border-subtle)', marginTop: 4 }}>
            <div style={{ height: 4, borderRadius: 99, width: Math.min(100, r * 100) + '%', background: r > 1 ? 'var(--hl-red-fg)' : 'var(--surface-accent)' }} />
          </div>
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center' }}>
        {a.member_ids.map((id) => {
          const m = av(members[id], id);
          return <Avatar key={id} a={m} size={22} title={m.name} style={{ marginRight: -5, border: '2px solid var(--surface-card)', lineHeight: '18px' }} />;
        })}
      </div>
    </div>
  );
}

const PHASE_GRID = 'minmax(120px,260px) minmax(80px,1fr) 52px 150px 110px 136px 28px';
const muted = { fontSize: 12, color: 'var(--text-muted)', whiteSpace: 'nowrap' } as const;
const span = (start: string | null, end: string | null) => (start ? `${dm(start)} → ${dm(end)}` : '–');

/** A phase's progress; the chevron lists the Tasks of the phase below it. */
function PhaseRow({ epic, p, tasks }: { epic: string; p: Schemas['PhaseRollup']; tasks: Schemas['PhaseTask'][] }) {
  const [open, setOpen] = useState(false);
  const toggle = () => setOpen((o) => !o);
  return (
    <div style={{ borderRadius: 12, background: 'var(--surface-hover)' }}>
      <div onClick={toggle} style={{ display: 'grid', gridTemplateColumns: PHASE_GRID, alignItems: 'center', gap: 14, padding: '8px 10px 8px 14px', cursor: 'pointer' }}>
        <span className="title ellipsis">{p.name}</span>
        <div style={{ height: 6, borderRadius: 6, background: 'var(--border-subtle)' }}>
          <div style={{ height: 6, borderRadius: 6, width: p.pct + '%', background: 'var(--surface-accent)' }} />
        </div>
        <span style={{ font: '700 16px/1 var(--font-display)', color: 'var(--text-heading)', textAlign: 'right' }}>{p.pct}%</span>
        <span style={muted}>{p.closed}/{p.total} sub-tasks done</span>
        <span style={muted}>{span(p.start, p.end)}</span>
        {tasks.length ? (
          <Link to="/anken/$epic/matrix" params={{ epic }} search={{ phase: p.name }} onClick={(e) => e.stopPropagation()} className="hv-bg"
            aria-label={`View ${p.name} as matrix`} style={{
              display: 'inline-flex', alignItems: 'center', justifySelf: 'end', gap: 6, height: 28, padding: '0 12px', borderRadius: 999,
              border: '1px solid var(--border-subtle)', background: 'var(--surface-card)', color: 'var(--text-heading)',
              font: 'var(--fw-semibold) 12px/1 var(--font-text)', textDecoration: 'none', whiteSpace: 'nowrap',
            }}><Icon name="grid-3x3" size={13} />View as Matrix</Link>
        ) : <span />}
        <button type="button" aria-expanded={open} aria-label={`${open ? 'Hide' : 'Show'} tasks of ${p.name}`} className="hv-bg"
          onClick={(e) => { e.stopPropagation(); toggle(); }} style={{
            display: 'flex', alignItems: 'center', justifyContent: 'center', width: 28, height: 28, padding: 0, borderRadius: 999,
            border: 'none', background: 'transparent', color: 'var(--text-muted)', cursor: 'pointer',
          }}><Icon name={open ? 'chevron-down' : 'chevron-right'} size={16} /></button>
      </div>
      <PhaseSchedule epic={epic} phase={p.name} s={p.schedule} />
      {open && (
        <ul aria-label={`Tasks of ${p.name}`} style={{ listStyle: 'none', margin: 0, padding: '0 6px 6px' }}>
          {!tasks.length && <li style={{ ...muted, padding: '6px 8px' }}>No Tasks in this phase.</li>}
          {tasks.map((t) => {
            const st = statusOf(t.status);
            return (
              <li key={t.key}>
                <Link to="/anken/$epic/matrix" params={{ epic }} search={{ tasks: t.key }} className="hv-bd" title={t.summary} style={{
                  display: 'grid', gridTemplateColumns: PHASE_GRID, alignItems: 'center', gap: 14, padding: '7px 4px 7px 8px', marginTop: 4,
                  borderRadius: 10, border: '1px solid var(--border-subtle)', background: 'var(--surface-card)', textDecoration: 'none', color: 'inherit',
                }}>
                  <span style={{ display: 'flex', alignItems: 'baseline', gap: 8, minWidth: 0 }}>
                    <span style={{ font: '600 11px/1 var(--font-mono)', color: 'var(--text-link)', flex: 'none' }}>{t.key}</span>
                    <span className="ellipsis heading" style={{ minWidth: 0 }}>{t.name || t.summary}</span>
                  </span>
                  <div style={{ height: 4, borderRadius: 4, background: 'var(--border-subtle)' }}>
                    <div style={{ height: 4, borderRadius: 4, width: t.pct + '%', background: 'var(--surface-accent)' }} />
                  </div>
                  <span style={{ fontWeight: 600, color: 'var(--text-heading)', textAlign: 'right' }}>{t.pct}%</span>
                  <span style={muted}>{t.closed}/{t.total} sub-tasks done</span>
                  <span style={muted}>{span(t.start, t.end)}</span>
                  <span style={{ ...muted, display: 'inline-flex', alignItems: 'center', gap: 6, justifySelf: 'end' }}>
                    <span className="dot" style={{ width: 7, height: 7, background: `var(--st-${st.v})` }} />{st.label}
                  </span>
                  <span />
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function Detail({ epic }: { epic: string }) {
  const q = useQuery(ankenDetailQuery(epic));
  const members = useMemberMap();
  const open = useOpenTicket();
  const canMembers = useCan('members');
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  const a = q.data.card;
  return (
    <section className="card" aria-label={`${a.key} details`} style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <div style={{ flex: 1 }}>
          <div style={{ font: '600 11px/1 var(--font-mono)', color: 'var(--text-muted)' }}>{a.key}</div>
          <div style={{ marginTop: 4, font: '700 18px/1.2 var(--font-display)', color: 'var(--text-heading)' }}>{a.title}</div>
        </div>
        {a.has_functions && (
          <Link to="/anken/$epic/matrix" params={{ epic: a.key }} className="hv-bg" style={{
            display: 'inline-flex', alignItems: 'center', gap: 8, height: 32, padding: '0 14px', borderRadius: 999, border: '1px solid var(--color-primary)',
            color: 'var(--text-heading)', font: 'var(--fw-semibold) var(--fs-small)/1 var(--font-text)', textDecoration: 'none',
          }}>Open Function Matrix <Icon name="arrow-right" size={14} /></Link>
        )}
      </div>
      <nav aria-label="Phases" style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {a.phases.map((p) => <PhaseRow key={p.name} epic={a.key} p={p} tasks={q.data.tasks.filter((t) => t.phase === p.name)} />)}
      </nav>
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
          <Icon name="triangle-alert" size={16} />
          <span className="title">Needs attention</span>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(250px,1fr))', gap: 12 }}>
          {q.data.attention.map((g) => {
            const s = ATTN[g.kind];
            return (
              <div key={g.kind} style={{ border: '1px solid var(--border-subtle)', borderRadius: 14, overflow: 'hidden' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 12px', background: 'var(--surface-hover)' }}>
                  <Icon name={s.icon} size={14} />
                  <span style={{ fontWeight: 600, color: 'var(--text-heading)', flex: 1 }}>{s.label}</span>
                  <span className="count" style={{ background: g.count ? 'var(--hl-red)' : 'var(--surface-hover)', color: g.count ? 'var(--hl-red-fg)' : 'var(--text-muted)' }}>{g.count}</span>
                </div>
                {g.items.map((c) => (
                  <div key={c.key} className="hv-bg" role="button" tabIndex={0} onClick={() => open(c.key)} onKeyDown={(e) => e.key === 'Enter' && open(c.key)}
                    style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '7px 12px', borderTop: '1px solid var(--border-subtle)', cursor: 'pointer' }}>
                    <span className="dot" style={{ width: 7, height: 7, background: `var(--st-${statusOf(c.status).v})` }} />
                    <span style={{ font: '600 11px/1 var(--font-mono)', color: 'var(--text-link)', flex: 'none' }}>{c.key}</span>
                    <span className="ellipsis heading" style={{ flex: 1, minWidth: 0 }}>{taskShort(c)}</span>
                    <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>{s.meta(c)}</span>
                    <Avatar a={av(members[c.assignee_id ?? ''], c.assignee_id ?? '–')} size={18} />
                  </div>
                ))}
                {!g.count && <div style={{ padding: '8px 12px', borderTop: '1px solid var(--border-subtle)', fontSize: 12, color: 'var(--text-muted)' }}>None.</div>}
              </div>
            );
          })}
        </div>
      </div>
      {q.data.not_members.length > 0 && <NotMembers epic={a.key} rows={q.data.not_members} />}
      {canMembers && <MembersPanel epic={a.key} />}
    </section>
  );
}
