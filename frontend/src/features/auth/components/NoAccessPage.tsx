import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { meQuery } from '../../../shared/api/queries';
import { Button, TextField } from '../../../shared/ds';
import { useLogout, useRequestAccess } from '../api';

const TEXT = {
  none: "You don't have access to Team Tracker yet.",
  requested: 'Your request was sent. The admin will review it.',
  rejected: 'Your request was declined.',
  disabled: 'Your access is disabled. Ask the admin.',
  active: 'You have access.',
} as const;

export function NoAccessPage() {
  const me = useQuery(meQuery).data;
  const navigate = useNavigate();
  const request = useRequestAccess();
  const logout = useLogout();
  const [note, setNote] = useState('');
  useEffect(() => { if (me?.access === 'active') void navigate({ to: '/my-work' }); }, [me, navigate]);
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
