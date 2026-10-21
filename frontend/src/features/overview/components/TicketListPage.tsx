import { useQuery } from '@tanstack/react-query';
import { getRouteApi, Link } from '@tanstack/react-router';
import { useMemberMap } from '../../../shared/api/queries';
import { dm } from '../../../shared/format/dates';
import { days, SCHEDULE_LABEL, signedH } from '../../../shared/format/labels';
import { useOpenTicket } from '../../../shared/state/ticket';
import { Loading, QueryError } from '../../../shared/ui/states';
import { ticketListQuery } from '../api';

const route = getRouteApi('/app/anken/$epic/tickets');
const DATE_LABELS = ['overdue', 'late_start', 'early_start', 'late_finish', 'early_finish'];

/** The sub-tasks of an anken (or one phase) behind a schedule label: the click-through of the Overview's counts. */
export function TicketListPage() {
  const { epic } = route.useParams();
  const { label, phase } = route.useSearch();
  const q = useQuery(ticketListQuery(epic, label, phase));
  const members = useMemberMap();
  const open = useOpenTicket();
  const byDate = DATE_LABELS.includes(label);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, width: '100%' }}>
      <div>
        <Link to="/anken/$epic" params={{ epic }} style={{ fontSize: 12 }}>← {epic}</Link>
        <h1 className="h1" style={{ marginTop: 2 }}>{SCHEDULE_LABEL[label]} · {epic}{phase ? ` · ${phase}` : ''}</h1>
      </div>
      {q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : !q.data.length ? (
        <div className="card" style={{ padding: 24, color: 'var(--text-muted)' }}>No tickets with this label.</div>
      ) : (
        <ul className="card" aria-label="Tickets" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          {q.data.map((c) => {
            const sc = c.schedule;
            const n = label.endsWith('start') ? sc.start_days : sc.finish_days;
            return (
              <li key={c.key} className="hv-bg" role="button" tabIndex={0} onClick={() => open(c.key)} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(c.key); } }}
                style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 14px', borderTop: '1px solid var(--border-subtle)', cursor: 'pointer' }}>
                <span style={{ font: '600 11px/1 var(--font-mono)', color: 'var(--text-link)', flex: 'none' }}>{c.key}</span>
                <span className="ellipsis heading" style={{ flex: 1, minWidth: 0 }}>{c.summary}</span>
                <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{members[c.assignee_id ?? '']?.name ?? '–'}</span>
                <span style={{ fontSize: 12, color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>{dm(c.planned_start)} → {dm(c.planned_end)}</span>
                <span style={{ fontSize: 12, fontWeight: 600, whiteSpace: 'nowrap', minWidth: 90, textAlign: 'right' }}>
                  {byDate ? (n > 0 ? days(n) : '') : signedH(sc.diff_h)}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
