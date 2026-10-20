import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { Button, IconButton, Select } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { useApprove } from '../api';

const ROLES = ['PM', 'PIM', 'BAL', 'BA'] as const;
const NEW = '';

export function ApproveDialog({ request, users, close }: { request: Schemas['AccessRequest']; users: Schemas['UserRow'][]; close: () => void }) {
  const approve = useApprove();
  const toast = useToast();
  const [role, setRole] = useState<(typeof ROLES)[number]>('BA');
  const [member, setMember] = useState(request.member_id ?? NEW);
  // Any member who cannot sign in yet, and always the one the request was matched to.
  const options = [
    { value: NEW, label: 'New member' },
    ...users.filter((u) => u.member.id === request.member_id || u.access !== 'active').map((u) => ({ value: u.member.id, label: `${u.member.name} · ${u.member.email || u.member.id}` })),
  ];
  const submit = () => approve.mutate({ id: request.id, body: { role, member_id: member || null } }, {
    onSuccess: close,
    onError: (e) => toast(errorMessage(e), true),
  });
  return (
    <div className="scrim" onClick={close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Approve access" onClick={(e) => e.stopPropagation()} style={{ width: 'min(480px,100%)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="dialog-title">Approve {request.name}</span>
          <IconButton icon="x" label="Close" size="sm" onClick={close} />
        </div>
        <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{request.email}</div>
        <Select label="Role" value={role} options={ROLES.map((r) => ({ value: r, label: r }))} onChange={(v) => setRole(v as typeof role)} />
        <Select label="Member" value={member} options={options} onChange={setMember} />
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="spacer" />
          <Button appearance="subtle" onClick={close}>Cancel</Button>
          <Button disabled={approve.isPending} onClick={submit}>Approve</Button>
        </div>
      </div>
    </div>
  );
}
