import { useQuery } from '@tanstack/react-query';
import { errorMessage } from '../../../shared/api/errors';
import type { Feature, Schemas } from '../../../shared/api/types';
import { IconButton, Select } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { Avatar, av } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { usePatchUser, usersQuery } from '../api';
import { RequestsSection } from './RequestsSection';

const FEATURES: [Feature, string][] = [
  ['my_work', 'My Work'], ['overview', 'Overview'], ['matrix', 'Matrix'], ['ticket', 'Ticket'], ['planning', 'Planning'],
  ['effort', 'Effort'], ['qa', 'Q&A'], ['calendar', 'Calendar'], ['members', 'Members'],
];
const LEVELS = [{ value: 'none', label: 'None' }, { value: 'read', label: 'Read' }, { value: 'write', label: 'Write' }];
const ROLES = [...['PM', 'PIM', 'BAL', 'BA', 'JM'].map((r) => ({ value: r, label: r })), { value: '', label: 'No role' }];
const ACCESS = [{ value: 'active', label: 'Active' }, { value: 'disabled', label: 'Disabled' }];
const COUNTRIES = [{ value: 'VN', label: 'Vietnam' }, { value: 'JP', label: 'Japan' }];
const GRID = `minmax(230px,1.3fr) 110px 70px 120px 110px repeat(${FEATURES.length}, 112px)`;

export function UsersPage() {
  const q = useQuery(usersQuery);
  const patch = usePatchUser();
  const toast = useToast();
  const update = (id: string, body: Schemas['UserPatchIn']) => patch.mutate({ id, body }, { onError: (e) => toast(errorMessage(e), true) });
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, width: '100%' }}>
      <div>
        <div className="eyebrow">Admin</div>
        <h1 className="h1" style={{ marginTop: 2 }}>Users</h1>
      </div>
      {q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : (
        <>
          <RequestsSection users={q.data} />
          <section className="card card-clip" aria-label="Users">
            <div style={{ overflowX: 'auto' }}>
              <div style={{ minWidth: 1600 }}>
                <div style={{ display: 'grid', gridTemplateColumns: GRID, gap: 12, padding: '8px 20px', background: 'var(--surface-hover)', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)' }}>
                  <span>User</span><span>Role</span><span>Admin</span><span>Access</span><span>Country</span>
                  {FEATURES.map(([k, label]) => <span key={k}>{label}</span>)}
                </div>
                {q.data.map((u) => <UserLine key={u.member.id} u={u} update={update} />)}
              </div>
            </div>
          </section>
        </>
      )}
    </div>
  );
}

function UserLine({ u, update }: { u: Schemas['UserRow']; update: (id: string, body: Schemas['UserPatchIn']) => void }) {
  const m = u.member;
  const id = m.id;
  const isAdmin = m.permission === 'admin' || u.bootstrap_admin;
  // "No access" covers none, requested and rejected: the admin can only move a user to Active or Disabled.
  const access = ACCESS.some((a) => a.value === u.access) ? ACCESS : [{ value: u.access, label: 'No access' }, ...ACCESS];
  return (
    <div style={{ display: 'grid', gridTemplateColumns: GRID, gap: 12, alignItems: 'center', padding: '8px 20px', borderTop: '1px solid var(--border-subtle)' }}>
      <span style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
        <Avatar a={av(m)} size={28} />
        <span style={{ minWidth: 0 }}>
          <b className="heading">{m.name}</b>
          <div className="ellipsis" style={{ fontSize: 11, color: 'var(--text-muted)' }}>{m.email || m.jira_account_id || '—'}</div>
        </span>
      </span>
      <Select aria-label={`${m.name} role`} value={m.role ?? ''} options={ROLES} onChange={(v) => update(id, v ? { role: v as 'BA' } : { clear_role: true })} />
      <input type="checkbox" aria-label={`${m.name} admin`} checked={isAdmin} disabled={u.bootstrap_admin}
        title={u.bootstrap_admin ? 'Set in TRACKER_ADMINS' : undefined} onChange={(e) => update(id, { admin: e.target.checked })} />
      <Select aria-label={`${m.name} access`} value={u.access} options={access} onChange={(v) => update(id, { access: v as 'active' })} />
      <Select aria-label={`${m.name} country`} value={m.country} options={COUNTRIES} onChange={(v) => update(id, { country: v as 'VN' })} />
      {FEATURES.map(([f, label]) => {
        const aria = `${m.name} ${label}`;
        // An admin has every feature; the role defaults in `effective` would mislead, and overrides do not apply.
        if (isAdmin) return <Select key={f} aria-label={aria} value="all" disabled options={[{ value: 'all', label: 'All' }]} onChange={() => {}} />;
        const over = u.overrides[f] !== undefined;
        return (
          <span key={f} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            <Select aria-label={aria} value={u.effective[f] ?? 'none'} options={LEVELS} style={{ flex: 1, minWidth: 0 }}
              onChange={(v) => update(id, { overrides: { [f]: v as 'read' } })} />
            {over && (
              <>
                <span aria-label="Overridden" title="Overridden" style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--color-accent)', flex: 'none' }} />
                <IconButton icon="undo-2" label="Reset to role default" size="sm" onClick={() => update(id, { overrides: { [f]: null } })} />
              </>
            )}
          </span>
        );
      })}
    </div>
  );
}
