import { useState } from 'react';
import { ApiError, errorMessage } from '../../../shared/api/errors';
import { useMe, useVisibleAnken } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button, IconButton, Select, TextField } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { useCreatePerson } from '../api';

type Role = NonNullable<Schemas['NewPersonIn']['role']>;

/** Adds a person who is not in the tracker yet; the admin gets the access request (a JM needs none). */
export function AddPersonDialog({ epic, close }: { epic: string; close: () => void }) {
  const me = useMe().data;
  const toast = useToast();
  const create = useCreatePerson();
  const visible = useVisibleAnken();
  const [f, setF] = useState({ email: '', name: '', role: 'BA' as Role });
  const [epics, setEpics] = useState<string[]>([epic]);
  const [err, setErr] = useState<ApiError | null>(null);
  const roles: Role[] = me?.admin ? ['BA', 'BAL', 'JM', 'PM', 'PIM'] : ['BA', 'BAL', 'JM'];
  const options = visible.some((a) => a.key === epic) ? visible : [{ key: epic, title: '' }, ...visible];
  const toggle = (key: string) => setEpics((e) => (e.includes(key) ? e.filter((k) => k !== key) : [...e, key]));
  const submit = () => create.mutate({ email: f.email.trim(), name: f.name.trim(), role: f.role, epics }, {
    onSuccess: (m) => { toast(`Added ${m.name}.${f.role === 'JM' ? '' : ' Access request sent to the admin.'}`); close(); },
    onError: (e) => (e instanceof ApiError && e.errors.length ? setErr(e) : toast(errorMessage(e), true)),
  });
  const field = (name: string) => err?.field(name);
  return (
    <div className="scrim" onClick={close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Add person" onClick={(e) => e.stopPropagation()} style={{ width: 'min(480px,100%)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="dialog-title" style={{ flex: 1 }}>Add person</span>
          <IconButton icon="x" label="Close" size="sm" onClick={close} />
        </div>
        <TextField label="E-mail" type="email" value={f.email} onChange={(v) => { setErr(null); setF((x) => ({ ...x, email: v })); }} aria-invalid={!!field('email')} />
        {field('email') && <div role="alert" style={{ fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)' }}>{field('email')}</div>}
        <TextField label="Name" value={f.name} onChange={(v) => { setErr(null); setF((x) => ({ ...x, name: v })); }} aria-invalid={!!field('name')} />
        {field('name') && <div role="alert" style={{ fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)' }}>{field('name')}</div>}
        <Select label="Role" value={f.role} options={roles.map((r) => ({ value: r, label: r }))} onChange={(v) => setF((x) => ({ ...x, role: v as Role }))} />
        <fieldset style={{ border: 0, padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 4 }}>
          <legend style={{ fontWeight: 600, color: 'var(--text-heading)', marginBottom: 4 }}>Anken</legend>
          {options.map((a) => (
            <label key={a.key} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13 }}>
              <input type="checkbox" checked={epics.includes(a.key)} onChange={() => { setErr(null); toggle(a.key); }} aria-label={a.key} />
              <span className="mono">{a.key}</span>
              {a.title && <span className="muted">{a.title}</span>}
            </label>
          ))}
          {field('epics') && <div role="alert" style={{ fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)' }}>{field('epics')}</div>}
        </fieldset>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="spacer" />
          <Button appearance="subtle" onClick={close}>Cancel</Button>
          <Button disabled={create.isPending || !f.email.trim() || !f.name.trim()} onClick={submit}>Add</Button>
        </div>
      </div>
    </div>
  );
}
