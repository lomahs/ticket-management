import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useVisibleAnken } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button, IconButton } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { usePatchUser } from '../api';

/** Replaces the anken a member belongs to. */
export function AnkenDialog({ user, close }: { user: Schemas['UserRow']; close: () => void }) {
  const toast = useToast();
  const patch = usePatchUser();
  const visible = useVisibleAnken();
  const [picked, setPicked] = useState<string[]>(user.anken);
  // A membership of an anken that is no longer listed stays visible, so it can be unticked.
  const options = [...visible, ...user.anken.filter((k) => !visible.some((a) => a.key === k)).map((key) => ({ key, title: '' }))];
  const toggle = (key: string) => setPicked((p) => (p.includes(key) ? p.filter((k) => k !== key) : [...p, key]));
  const submit = () => patch.mutate({ id: user.member.id, body: { anken: [...picked].sort() } }, {
    onSuccess: close,
    onError: (e) => toast(errorMessage(e), true),
  });
  return (
    <div className="scrim" onClick={close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Edit anken" onClick={(e) => e.stopPropagation()} style={{ width: 'min(480px,100%)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="dialog-title" style={{ flex: 1 }}>Anken of {user.member.name}</span>
          <IconButton icon="x" label="Close" size="sm" onClick={close} />
        </div>
        <fieldset style={{ border: 0, padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 4 }}>
          {options.map((a) => (
            <label key={a.key} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13 }}>
              <input type="checkbox" checked={picked.includes(a.key)} onChange={() => toggle(a.key)} aria-label={a.key} />
              <span className="mono">{a.key}</span>
              {a.title && <span className="muted">{a.title}</span>}
            </label>
          ))}
        </fieldset>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="spacer" />
          <Button appearance="subtle" onClick={close}>Cancel</Button>
          <Button disabled={patch.isPending} onClick={submit}>Save</Button>
        </div>
      </div>
    </div>
  );
}
