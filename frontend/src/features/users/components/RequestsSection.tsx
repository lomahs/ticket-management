import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { Button } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { Loading, QueryError } from '../../../shared/ui/states';
import { pendingRequestsQuery, useReject } from '../api';
import { ApproveDialog } from './ApproveDialog';

const when = (iso: string) => iso.slice(0, 16).replace('T', ' ');

export function RequestsSection({ users }: { users: Schemas['UserRow'][] }) {
  const q = useQuery(pendingRequestsQuery);
  const reject = useReject();
  const toast = useToast();
  const [approving, setApproving] = useState<Schemas['AccessRequest'] | null>(null);
  return (
    <section className="card" aria-label="Access requests" style={{ padding: 0 }}>
      <div style={{ padding: '14px 20px', display: 'flex', alignItems: 'baseline', gap: 8 }}>
        <span className="title">Access requests</span>
        {q.data && q.data.length > 0 && <span className="count">{q.data.length}</span>}
      </div>
      {q.isPending ? <Loading /> : q.isError ? <div style={{ padding: 16 }}><QueryError error={q.error} /></div> : q.data.length === 0 ? (
        <div style={{ padding: '0 20px 14px', fontSize: 12, color: 'var(--text-muted)' }}>No pending requests.</div>
      ) : q.data.map((r) => (
        <div key={r.id} style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap', padding: '10px 20px', borderTop: '1px solid var(--border-subtle)' }}>
          <div style={{ flex: '1 1 260px', minWidth: 0 }}>
            <b className="heading">{r.name}</b> <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{r.email}</span>
            {r.note && <div style={{ fontSize: 12, color: 'var(--text-body)' }}>{r.note}</div>}
          </div>
          <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{when(r.requested_at)}</span>
          <Button size="sm" appearance="subtle" disabled={reject.isPending} onClick={() => reject.mutate(r.id, { onError: (e) => toast(errorMessage(e), true) })}>Reject</Button>
          <Button size="sm" onClick={() => setApproving(r)}>Approve…</Button>
        </div>
      ))}
      {approving && <ApproveDialog request={approving} users={users} close={() => setApproving(null)} />}
    </section>
  );
}
