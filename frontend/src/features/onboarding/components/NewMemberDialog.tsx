import { useState } from 'react';
import { ApiError, errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { Button, IconButton, Select, TextField } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { useCreateMember } from '../api';

type Role = NonNullable<Schemas['NewPersonIn']['role']>;
const ROLES: Role[] = ['BA', 'BAL', 'JM', 'PM', 'PIM'];

/** A person not in the tracker yet, added to this anken by name and role; the e-mail (their sign-in) can come later. */
export function NewMemberDialog({ epic, close }: { epic: string; close: () => void }) {
  const toast = useToast();
  const create = useCreateMember(epic);
  const [f, setF] = useState({ name: '', role: 'BA' as Role, email: '' });
  const [err, setErr] = useState<ApiError | null>(null);
  const edit = (p: Partial<typeof f>) => { setErr(null); setF((x) => ({ ...x, ...p })); };
  const email = f.email.trim();
  const submit = () => create.mutate({ name: f.name.trim(), role: f.role, email: email || null }, {
    onSuccess: (m) => { toast(`Added ${m.name}.${email && f.role !== 'JM' ? ' Access request sent to the admin.' : ''}`); close(); },
    onError: (e) => (e instanceof ApiError && e.errors.length ? setErr(e) : toast(errorMessage(e), true)),
  });
  const field = (name: string) => err?.field(name);
  const alert = (name: string) => field(name) && <div role="alert" style={{ fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)' }}>{field(name)}</div>;
  return (
    <div className="scrim" onClick={close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="New member" onClick={(e) => e.stopPropagation()} style={{ width: 'min(440px,100%)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="dialog-title" style={{ flex: 1 }}>New member</span>
          <IconButton icon="x" label="Close" size="sm" onClick={close} />
        </div>
        <TextField label="Name" value={f.name} onChange={(v) => edit({ name: v })} aria-invalid={!!field('name')} />
        {alert('name')}
        <Select label="Role" value={f.role} options={ROLES.map((r) => ({ value: r, label: r }))} onChange={(v) => edit({ role: v as Role })} />
        <TextField label="E-mail (optional)" type="email" value={f.email} onChange={(v) => edit({ email: v })} aria-invalid={!!field('email')} />
        {alert('email')}
        <span className="eyebrow">Without an e-mail they cannot sign in until the admin sets one in Users.</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="spacer" />
          <Button appearance="subtle" onClick={close}>Cancel</Button>
          <Button disabled={create.isPending || !f.name.trim()} onClick={submit}>Add</Button>
        </div>
      </div>
    </div>
  );
}
