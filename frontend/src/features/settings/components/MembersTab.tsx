import { errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { useMembers } from '../../../shared/api/queries';
import { Select } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { Avatar, av } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { usePatchMember } from '../api';
import { Section } from './common';

const GRID = 'minmax(190px,1.3fr) minmax(190px,1.2fr) 160px 120px 230px';

export function MembersTab() {
  const q = useMembers();
  const patchMember = usePatchMember();
  const toast = useToast();
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  const update = (id: string, patch: Omit<Schemas['MemberPatchIn'], 'clear_role'> & { clear_role?: boolean }) => patchMember.mutate({ id, body: { clear_role: false, ...patch } }, { onError: (e) => toast(errorMessage(e), true) });
  return (
    <Section pad={false} title="Members & roles" sub="Members appear when they are first seen on a synced issue. Role decides which step a member is assigned to; country decides which holidays apply. Admins can create, edit and delete data; Users can only view it.">
      <div style={{ overflowX: 'auto' }}>
        <div style={{ minWidth: 900 }}>
          <div style={{ display: 'grid', gridTemplateColumns: GRID, gap: 12, padding: '8px 20px', background: 'var(--surface-hover)', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)' }}>
            <span>Member</span><span>Jira account</span><span>Role</span><span>Country</span><span>Permission</span>
          </div>
          {q.data.map((m) => (
            <div key={m.id} style={{ display: 'grid', gridTemplateColumns: GRID, gap: 12, alignItems: 'center', padding: '8px 20px', borderTop: '1px solid var(--border-subtle)' }}>
              <span style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <Avatar a={av(m)} size={28} />
                <span><b className="heading">{m.name}</b><div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{m.full_name}</div></span>
              </span>
              <span style={{ fontSize: 12, color: 'var(--text-heading)' }}>{m.email || m.jira_account_id || '—'}</span>
              <Select aria-label={`${m.name} role`} value={m.role ?? ''}
                options={[{ value: '', label: '— No role —' }, { value: 'BA', label: 'BA' }, { value: 'BAL', label: 'BA Leader' }, { value: 'JM', label: 'Japan Member' }]}
                onChange={(v) => update(m.id, v ? { role: v as 'BA' } : { clear_role: true })} />
              <Select aria-label={`${m.name} country`} value={m.country} options={[{ value: 'VN', label: 'Vietnam' }, { value: 'JP', label: 'Japan' }]}
                onChange={(v) => update(m.id, { country: v as 'VN' })} />
              <Select aria-label={`${m.name} permission`} value={m.permission ?? 'user'}
                options={[{ value: 'admin', label: 'Admin · create, edit, delete' }, { value: 'user', label: 'User · view only' }]}
                onChange={(v) => update(m.id, { permission: v as 'admin' })} />
            </div>
          ))}
        </div>
      </div>
    </Section>
  );
}
