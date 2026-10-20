import { useQuery } from '@tanstack/react-query';
import { getRouteApi, useNavigate } from '@tanstack/react-router';
import { useEffect, useState, type FormEvent } from 'react';
import { meQuery } from '../../../shared/api/queries';
import { Button, TextField } from '../../../shared/ds';
import { errorMessage } from '../../../shared/api/errors';
import { authInfoQuery, useLogin } from '../api';

const route = getRouteApi('/login');

/** Only same-site paths are followed after sign-in. */
const safeNext = (next: string | undefined) => (next && next.startsWith('/') && !next.startsWith('//') ? next : '/my-work');

export function LoginPage() {
  const { next } = route.useSearch();
  const navigate = useNavigate();
  const me = useQuery(meQuery).data;
  const info = useQuery(authInfoQuery).data;
  const login = useLogin();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const go = () => navigate({ to: safeNext(next) });
  useEffect(() => { if (me?.access === 'active') void navigate({ to: safeNext(next) }); }, [me, next, navigate]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    login.mutate({ username: username.trim(), password }, { onSuccess: () => void go() });
  };

  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', padding: 16 }}>
      <form className="card" onSubmit={submit} style={{ width: 360, maxWidth: '100%', padding: 24, display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div>
          <h1 className="h1">Team Tracker</h1>
          <div className="eyebrow">Sign in with your company account</div>
        </div>
        <TextField label="Username" value={username} onChange={setUsername} autoComplete="username" autoFocus />
        <TextField label="Password" type="password" value={password} onChange={setPassword} autoComplete="current-password" />
        <Button type="submit" fullWidth disabled={login.isPending || !username || !password}>Sign in</Button>
        {login.isError && <div role="alert" style={{ color: 'var(--hl-red-fg)', fontSize: 12 }}>{errorMessage(login.error)}</div>}
        {info?.demo_password && (
          <div className="eyebrow">Demo · users: {info.demo_users.join(', ')} · password: {info.demo_password}</div>
        )}
      </form>
    </div>
  );
}
