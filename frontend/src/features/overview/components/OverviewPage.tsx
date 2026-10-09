import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from '@tanstack/react-router';
import type { Schemas } from '../../../shared/api/types';
import { useMemberMap } from '../../../shared/api/queries';
import { Icon, type IconName } from '../../../shared/ds';
import { dm } from '../../../shared/format/dates';
import { PHASE_SHORT, statusOf } from '../../../shared/format/labels';
import { taskShort } from '../../../shared/format/task';
import { useOpenTicket } from '../../../shared/state/ticket';
import { Avatar, av } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { ankenDetailQuery, overviewQuery } from '../api';

const PHASE_W: Record<string, number> = { Prepare: 0.15, Implement_Coding: 0.6, 'Function Unit Test': 0.25 };
const ATTN: Record<string, { label: string; icon: IconName; meta: (c: Schemas['TaskCard']) => string }> = {
  overdue: { label: 'Overdue', icon: 'alarm-clock', meta: (c) => `late ${c.late_days}d` },
  stuck_resolved: { label: 'Stuck in Resolved', icon: 'pause-circle', meta: (c) => `${c.held_days} days` },
  many_rounds: { label: 'Many review rounds', icon: 'refresh-cw', meta: (c) => `round ${c.rounds}` },
  waiting_client: { label: 'Waiting long on client (JM)', icon: 'hourglass', meta: (c) => `${c.held_days} days` },
};

export function OverviewPage() {
  const params = useParams({ strict: false }) as { epic?: string };
  const q = useQuery(overviewQuery);
  const projects = [...new Set((q.data ?? []).map((a) => a.project))].join(', ');
  const selected = params.epic ?? q.data?.[0]?.key;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18, width: '100%' }}>
      <div>
        <div className="eyebrow">{q.data ? `Project ${projects} · ${q.data.length} active anken` : ' '}</div>
        <h1 className="h1" style={{ marginTop: 2 }}>Anken Overview</h1>
      </div>
      {q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : !q.data.length ? (
        <div className="card" style={{ padding: 24, color: 'var(--text-muted)' }}>No epics are tracked yet. Add them in <Link to="/settings/$tab" params={{ tab: 'jira' }}>Settings → Jira</Link>.</div>
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
      </div>
      <div>
        <div style={{ display: 'flex', gap: 3, height: 8 }}>
          {a.phases.map((p) => (
            <div key={p.name} title={`${p.name} ${p.pct}%`} style={{ flex: PHASE_W[p.name] ?? 0.2, borderRadius: 99, background: 'var(--surface-tint)', overflow: 'hidden' }}>
              <div style={{ height: '100%', width: p.pct + '%', background: 'var(--surface-accent)' }} />
            </div>
          ))}
        </div>
        <div style={{ display: 'flex', gap: 10, marginTop: 6, fontSize: 11, color: 'var(--text-muted)', flexWrap: 'wrap' }}>
          {a.phases.map((p) => <span key={p.name} style={{ whiteSpace: 'nowrap' }}>{PHASE_SHORT[p.name] ?? p.name} <b className="heading">{p.pct}%</b></span>)}
        </div>
      </div>
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

function Detail({ epic }: { epic: string }) {
  const q = useQuery(ankenDetailQuery(epic));
  const members = useMemberMap();
  const open = useOpenTicket();
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  const a = q.data.card;
  const cur = a.phases.findIndex((p) => p.pct < 100);
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
      <div style={{ display: 'flex', alignItems: 'stretch', gap: 6, flexWrap: 'wrap' }}>
        {a.phases.map((p, i) => (
          <div key={p.name} style={{ flex: '1 1 200px', display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
            <div style={{ flex: 1, padding: '12px 14px', borderRadius: 14, minWidth: 0, background: i === cur ? 'var(--surface-tint)' : 'var(--surface-hover)' }}>
              <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
                <span className="title">{p.name}</span>
                <span className="spacer" />
                <span style={{ font: '700 22px/1 var(--font-display)', color: 'var(--text-heading)' }}>{p.pct}%</span>
              </div>
              <div style={{ height: 5, borderRadius: 6, background: 'var(--border-subtle)', margin: '10px 0 8px' }}>
                <div style={{ height: 5, borderRadius: 6, width: p.pct + '%', background: 'var(--surface-accent)' }} />
              </div>
              <div style={{ display: 'flex', fontSize: 11, color: 'var(--text-muted)' }}>
                <span style={{ flex: 1 }}>{p.start ? `${dm(p.start)} – ${dm(p.end)}` : '–'}</span><span>{p.closed}/{p.total} sub-task closed</span>
              </div>
            </div>
            {i < a.phases.length - 1 && <span style={{ color: 'var(--text-muted)', display: 'flex' }}><Icon name="chevron-right" size={16} /></span>}
          </div>
        ))}
      </div>
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
    </section>
  );
}
