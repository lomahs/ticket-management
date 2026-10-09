import { Link } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import { SyncPill } from '../../sync';

/** Title row and the Matrix / Add function tabs (each tab is its own URL). */
export function MatrixHeader({ epic, title, phase, tab }: { epic: string; title?: string; phase?: string; tab: 'matrix' | 'new' }) {
  const tabLink = (to: 'matrix' | 'new', label: ReactNode) => {
    const on = tab === to;
    return (
      <Link
        to={to === 'matrix' ? '/anken/$epic/matrix' : '/anken/$epic/matrix/new'}
        params={{ epic }}
        role="tab"
        aria-selected={on}
        style={{
          padding: '10px 0', marginBottom: -1, textDecoration: 'none', font: 'var(--fw-semibold) var(--fs-small)/1.2 var(--font-text)',
          color: on ? 'var(--text-heading)' : 'var(--text-muted)', borderBottom: `2px solid ${on ? 'var(--color-accent)' : 'transparent'}`,
        }}
      >{label}</Link>
    );
  };
  return (
    <>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <div style={{ flex: '1 1 260px', minWidth: 0 }}>
          <div className="eyebrow">{[epic, title, phase].filter(Boolean).join(' · ')}</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap', marginTop: 2 }}>
            <h1 className="h1">Function Matrix</h1>
            <SyncPill />
          </div>
        </div>
      </div>
      <div role="tablist" style={{ display: 'flex', gap: 24, borderBottom: '1px solid var(--border-subtle)' }}>
        {tabLink('matrix', 'Matrix')}
        {tabLink('new', 'Add function')}
      </div>
    </>
  );
}
