import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useCan, useMe, useMemberMap, VIEW_ONLY } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button, Icon, Select } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { Avatar, av } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { ankenMembersQuery, useAddAnkenMember, usePatchPerson, useRemoveAnkenMember } from '../api';
import { AddPersonDialog } from './AddPersonDialog';
import { AllocationDialog } from './AllocationDialog';

const NO_ROLE = '';
const ROLES = ['BA', 'BAL', 'JM'];
const ADMIN_ROLES = ['PM', 'PIM', ...ROLES];

/** Anken members: who may see this anken, their role, and who can be added. Needs read on `members`. */
export function MembersPanel({ epic }: { epic: string }) {
  const q = useQuery(ankenMembersQuery(epic));
  const me = useMe().data;
  const write = useCan('members', 'write');
  const toast = useToast();
  const add = useAddAnkenMember(epic);
  const remove = useRemoveAnkenMember(epic);
  const patch = usePatchPerson();
  const [pick, setPick] = useState('');
  const [confirming, setConfirming] = useState<string | null>(null);
  const [dialog, setDialog] = useState(false);
  const [allocating, setAllocating] = useState<string | null>(null);
  const fail = (e: unknown) => toast(errorMessage(e), true);
  const roles = me?.admin ? ADMIN_ROLES : ROLES;
  const candidate = pick || q.data?.candidates[0]?.id || '';

  return (
    <section className="card" aria-label="Members" style={{ padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <Icon name="users" size={16} />
        <h2 className="title" style={{ flex: 1, margin: 0 }}>Members</h2>
        <Button appearance="secondary" size="sm" iconBefore="plus" disabled={!write} title={write ? undefined : VIEW_ONLY} onClick={() => setDialog(true)}>Add person…</Button>
      </div>
      {q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : (
        <>
          <div role="list" aria-label="Anken members" style={{ display: 'flex', flexDirection: 'column' }}>
            {q.data.members.map(({ member: m, access, added_at, added_by }) => (
              <MemberRow key={m.id} m={m} access={access} addedAt={added_at} addedBy={added_by} roles={roles} write={write}
                locked={!me?.admin && (m.role === 'PM' || m.role === 'PIM')} confirming={confirming === m.id} onAllocate={() => setAllocating(m.id)}
                onRole={(role) => patch.mutate({ id: m.id, body: role === NO_ROLE ? { clear_role: true } : { role: role as Schemas['Member']['role'] } }, { onError: fail })}
                onAsk={() => setConfirming(m.id)} onCancel={() => setConfirming(null)}
                onRemove={() => remove.mutate(m.id, { onSuccess: () => setConfirming(null), onError: fail })} />
            ))}
            {!q.data.members.length && <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>No members yet.</div>}
          </div>
          {write && q.data.candidates.length > 0 && (
            <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8 }}>
              <Select label="Add member" value={candidate} onChange={setPick} style={{ flex: 1 }}
                options={q.data.candidates.map((c) => ({ value: c.id, label: c.role ? `${c.name} · ${c.role}` : c.name }))} />
              <Button disabled={!candidate || add.isPending} onClick={() => add.mutate(candidate, { onSuccess: () => setPick(''), onError: fail })}>Add</Button>
            </div>
          )}
        </>
      )}
      {dialog && <AddPersonDialog epic={epic} close={() => setDialog(false)} />}
      {allocating && <AllocationDialog memberId={allocating} close={() => setAllocating(null)} />}
    </section>
  );
}

function MemberRow({ m, access, addedAt, addedBy, roles, write, locked, confirming, onAllocate, onRole, onAsk, onCancel, onRemove }: {
  m: Schemas['Member']; access: Schemas['AnkenMemberRow']['access']; addedAt: string; addedBy: string; roles: string[]; write: boolean; locked: boolean;
  confirming: boolean; onAllocate: () => void; onRole: (role: string) => void; onAsk: () => void; onCancel: () => void; onRemove: () => void;
}) {
  const names = useMemberMap();
  // added_by is the adder's e-mail, or "seed" for the first memberships made from Jira activity.
  const email = addedBy.toLowerCase();
  const by = Object.values(names).find((n) => n.email && n.email.toLowerCase() === email)?.name ?? addedBy;
  const added = !addedBy ? '' : addedBy === 'seed' ? ` · added from Jira activity on ${addedAt}` : ` · added by ${by} on ${addedAt}`;
  return (
    <div role="listitem" style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 0', borderTop: '1px solid var(--border-subtle)' }}>
      <Avatar a={av(m, m.id)} size={26} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="ellipsis heading" style={{ fontWeight: 600 }}>{m.name}</div>
        <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{m.role ?? 'No role'} · access {access}{added}</div>
      </div>
      <Button size="sm" appearance="subtle" iconBefore="timer" aria-label={`Allocation… ${m.name}`} onClick={onAllocate}>Allocation…</Button>
      {write && !locked ? (
        <Select aria-label={`Role of ${m.name}`} value={m.role ?? NO_ROLE} style={{ width: 120 }} onChange={onRole}
          options={[{ value: NO_ROLE, label: 'No role' }, ...roles.map((r) => ({ value: r, label: r }))]} />
      ) : null}
      {write && (confirming ? (
        <>
          <Button size="sm" onClick={onRemove}>Confirm remove</Button>
          <Button size="sm" appearance="subtle" onClick={onCancel}>Cancel</Button>
        </>
      ) : <Button size="sm" appearance="subtle" iconBefore="trash-2" aria-label={`Remove ${m.name}`} onClick={onAsk}>Remove</Button>)}
    </div>
  );
}

/** People who log work on this anken without being members. */
export function NotMembers({ epic, rows }: { epic: string; rows: Schemas['NotMember'][] }) {
  const names = useMemberMap();
  const write = useCan('members', 'write');
  const toast = useToast();
  const add = useAddAnkenMember(epic);
  return (
    <section aria-label="Working here but not a member" style={{ border: '1px solid var(--hl-amber-fg)', background: 'var(--hl-amber)', borderRadius: 14, padding: '10px 14px', display: 'flex', flexDirection: 'column', gap: 6 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontWeight: 600, color: 'var(--hl-amber-fg)' }}>
        <Icon name="triangle-alert" size={14} />
        <span>Working here but not a member</span>
      </div>
      {rows.map((r) => (
        <div key={r.member_id} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ flex: 1 }}>{names[r.member_id]?.name ?? r.member_id} — {r.open_subtasks} open sub-tasks, {r.logged_h}h logged</span>
          <Button size="sm" appearance="secondary" disabled={!write || add.isPending} title={write ? undefined : VIEW_ONLY}
            onClick={() => add.mutate(r.member_id, { onError: (e) => toast(errorMessage(e), true) })}>Add to anken</Button>
        </div>
      ))}
    </section>
  );
}

