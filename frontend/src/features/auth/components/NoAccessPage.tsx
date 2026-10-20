import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { meQuery } from '../../../shared/api/queries';
import { Button, TextField } from '../../../shared/ds';
import { homeOf } from '../../../shared/nav';
import { useLogout, useRequestAccess } from '../api';

const TEXT = {
  none: "You don't have access to Team Tracker yet.",
  requested: 'Your request was sent. The admin will review it.',
  rejected: 'Your request was declined.',
  disabled: 'Your access is disabled. Ask the admin.',
  active: "You have access, but to none of the screens. Ask the admin.",
} as const;

export function NoAccessPage() {
  const me = useQuery(meQuery).data;
  const navigate = useNavigate();
  const request = useRequestAccess();
  const logout = useLogout();
  const [note, setNote] = useState('');
  const home = me?.access === 'active' ? homeOf(me) : null;
  // An active user goes to their first screen; one who may read none stays here with the message.
  useEffect(() => { if (home) void navigate({ to: home }); }, [home, navigate]);
  if (!me) return null;
  const canRequest = me.access === 'none' || me.access === 'rejected';
  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', padding: 16 }}>
      <div className="card" style={{ width: 400, maxWidth: '100%', padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
        <h1 className="h1">Team Tracker</h1>
        <p style={{ margin: 0 }}>{TEXT[me.access]}</p>
        <div className="eyebrow">Signed in as {me.email}</div>
        {canRequest && (
          <>
            <TextField label="Note (optional)" value={note} onChange={setNote} />
            <Button disabled={request.isPending} onClick={() => request.mutate(note.trim() || null)}>Request access</Button>
            {request.isError && <div role="alert" style={{ color: 'var(--hl-red-fg)', fontSize: 12 }}>{errorMessage(request.error)}</div>}
          </>
        )}
        <Button appearance="link" onClick={() => logout.mutate()}>Sign out</Button>
      </div>
    </div>
  );
}
