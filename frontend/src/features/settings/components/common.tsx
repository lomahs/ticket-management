import { useEffect, useState, type ReactNode } from 'react';

/** The value, once it has stopped changing for `ms` milliseconds. */
export function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => { const t = setTimeout(() => setV(value), ms); return () => clearTimeout(t); }, [value, ms]);
  return v;
}

export function Section({ title, sub, children, pad = true }: { title: string; sub: ReactNode; children: ReactNode; pad?: boolean }) {
  return (
    <section className={'card' + (pad ? '' : ' card-clip')} aria-label={title}
      style={pad ? { padding: '18px 20px', display: 'flex', flexDirection: 'column', gap: 16 } : undefined}>
      <div style={pad ? undefined : { padding: '16px 20px' }}>
        <div className="title">{title}</div>
        <div className="eyebrow">{sub}</div>
      </div>
      {children}
    </section>
  );
}

export const ErrText = ({ children }: { children: ReactNode }) => (
  <span role="alert" style={{ fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)', alignSelf: 'center' }}>{children}</span>
);

export const Busy = ({ children }: { children: ReactNode }) => (
  <span role="status" style={{ fontSize: 12, color: 'var(--text-muted)', alignSelf: 'center' }}>{children}</span>
);
