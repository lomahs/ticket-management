import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { useMemberMap } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { statusOf } from '../../../shared/format/labels';
import { useAnkenScope } from '../../../shared/state/anken';
import { useOpenTicket } from '../../../shared/state/ticket';
import { Avatar, av } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { ticketCheckQuery } from '../api';

const route = getRouteApi('/app/ticket-check');

/** What each check means, in the order the API lists them. */
const CHECKS: Record<string, { label: string; tip: string }> = {
  rule: { label: 'Name outside the rule', tip: 'Neither a function step nor a single ticket: fix the Summary, or the name rule in Settings' },
  no_end: { label: 'No planned end', tip: 'An open sub-task without a Planned End: it cannot be late, and plans skip it' },
  no_start: { label: 'No planned start', tip: 'An open sub-task without a Planned Start' },
  no_assignee: { label: 'No assignee', tip: 'An open sub-task nobody is assigned to' },
  no_labels: { label: 'No labels', tip: 'The sub-task has no Jira label (the project label)' },
  no_estimate: { label: 'No estimate', tip: 'An open sub-task without an Original Estimate: effort and burndown skip it' },
  not_member: { label: 'Assignee not in the anken', tip: 'The assignee is not a member of the anken; add them in Anken Overview' },
  closed_no_log: { label: 'Closed without hours', tip: 'Closed, but no work was logged on it' },
  step_order: { label: 'Step started too early', tip: 'A function step started while an earlier step was not Resolved or Closed' },
};

const GRID = '100px minmax(240px,2fr) minmax(160px,1fr) 140px 90px minmax(220px,1.6fr)';

export function TicketCheckPage() {
  const { anken } = useAnkenScope();
  const { check } = route.useSearch();
  const navigate = route.useNavigate();
  const q = useQuery(ticketCheckQuery(anken));
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, width: '100%', minWidth: 0 }}>
      <div>
        <div className="eyebrow">Jira data the other screens rely on · fixed tickets drop off after the next sync</div>
        <h1 className="h1" style={{ margin: '2px 0 0' }}>Ticket Check</h1>
      </div>
      {q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : (
        <Checks v={q.data} check={check} pick={(c) => navigate({ search: (s) => ({ ...s, check: c && c !== check ? c : undefined }) })} />
      )}
    </div>
  );
}

function Checks({ v, check, pick }: { v: Schemas['TicketCheckView']; check?: string; pick: (c?: string) => void }) {
  const rows = check ? v.rows.filter((r) => r.problems.includes(check)) : v.rows;
  return (
    <>
      <div role="group" aria-label="Checks" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(190px,1fr))', gap: 10 }}>
        {v.counts.map((c) => {
          const on = c.check === check;
          return (
            <button key={c.check} type="button" aria-pressed={on} title={CHECKS[c.check]?.tip} onClick={() => pick(c.check)} className="hv-bg" style={{
              display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 4, padding: '10px 14px', borderRadius: 12, cursor: 'pointer', textAlign: 'left',
              border: `1px solid ${on ? 'var(--border-focus)' : 'var(--border-subtle)'}`, background: on ? 'var(--surface-tint)' : 'var(--surface-card)',
              boxShadow: on ? 'inset 0 0 0 1px var(--border-focus)' : 'none', font: 'inherit', color: 'inherit',
            }}>
              <span style={{ font: '700 22px/1.1 var(--font-display)', color: c.count ? 'var(--hl-red-fg)' : 'var(--text-muted)' }}>{c.count}</span>
              <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-heading)' }}>{CHECKS[c.check]?.label ?? c.check}</span>
            </button>
          );
        })}
      </div>
      <section className="card" aria-label="Tickets to fix" style={{ overflowX: 'auto' }}>
        <div style={{ minWidth: 980 }}>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)' }}>
            <span className="title">{check ? CHECKS[check]?.label ?? check : 'Every problem'}</span>
            <span className="eyebrow">{rows.length} of {v.checked} sub-tasks{check ? '' : ' need a fix'}</span>
            <span className="spacer" />
            {check && <button type="button" className="hv-underline" onClick={() => pick()} style={{ border: 0, background: 'none', color: 'var(--text-link)', cursor: 'pointer', font: 'inherit', fontSize: 12 }}>Show every problem</button>}
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: GRID, gap: 12, padding: '8px 16px', background: 'var(--surface-hover)', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)' }}>
            <span>Key</span><span>Summary</span><span>Anken · sub-phase</span><span>Assignee</span><span>Status</span><span>Problems</span>
          </div>
          {rows.map((r) => <Row key={r.card.key} r={r} />)}
          {!rows.length && <div className="empty-row">{v.rows.length ? 'No sub-task has this problem.' : `All ${v.checked} sub-tasks look fine.`}</div>}
        </div>
      </section>
    </>
  );
}

function Row({ r }: { r: Schemas['CheckRow'] }) {
  const open = useOpenTicket();
  const members = useMemberMap();
  const c = r.card;
  const a = c.assignee_id ? av(members[c.assignee_id], c.assignee_id) : null;
  return (
    <div style={{ display: 'grid', gridTemplateColumns: GRID, gap: 12, alignItems: 'center', padding: '8px 16px', borderTop: '1px solid var(--border-subtle)', fontSize: 13 }}>
      <button type="button" className="key hv-underline" onClick={() => open(c.key)} style={{ justifySelf: 'start', padding: 0, border: 0, background: 'none', cursor: 'pointer' }}>{c.key}</button>
      <span className="heading" style={{ minWidth: 0, overflowWrap: 'anywhere' }}>{c.summary}</span>
      <span style={{ minWidth: 0, color: 'var(--text-muted)' }}><span className="mono">{r.epic_key}</span>{r.task_name ? ` · ${r.task_name}` : ''}</span>
      <span style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
        {a ? <><Avatar a={a} size={20} /><span className="ellipsis">{a.name}</span></> : <span className="muted">—</span>}
      </span>
      <span>{statusOf(c.status).label}</span>
      <span style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
        {r.problems.map((p) => (
          <span key={p} title={CHECKS[p]?.tip} style={{ padding: '2px 8px', borderRadius: 999, background: 'var(--hl-red)', color: 'var(--hl-red-fg)', font: '600 11px/1.5 var(--font-text)', whiteSpace: 'nowrap' }}>
            {CHECKS[p]?.label ?? p}
          </span>
        ))}
      </span>
    </div>
  );
}
