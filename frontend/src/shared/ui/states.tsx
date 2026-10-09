import { errorMessage } from '../api/errors';

export function Loading({ label = 'Loading…' }: { label?: string }) {
  return <div role="status" style={{ padding: 24, color: 'var(--text-muted)' }}>{label}</div>;
}

export function QueryError({ error }: { error: unknown }) {
  return (
    <div role="alert" className="card" style={{ padding: '14px 16px', color: 'var(--hl-red-fg)', fontWeight: 600 }}>
      {errorMessage(error)}
    </div>
  );
}
