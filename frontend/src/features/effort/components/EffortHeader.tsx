import { Link } from '@tanstack/react-router';

export function EffortHeader({ tab }: { tab: 'timesheet' | 'analysis' }) {
  const link = (to: '/effort/timesheet' | '/effort/analysis', label: string, on: boolean) => (
    <Link to={to} role="tab" aria-selected={on} style={{
      padding: '10px 0', marginBottom: -1, textDecoration: 'none', font: 'var(--fw-semibold) var(--fs-small)/1.2 var(--font-text)',
      color: on ? 'var(--text-heading)' : 'var(--text-muted)', borderBottom: `2px solid ${on ? 'var(--color-accent)' : 'transparent'}`,
    }}>{label}</Link>
  );
  return (
    <>
      <div>
        <div className="eyebrow">Worklogs synced from Jira · all projects</div>
        <h1 className="h1" style={{ marginTop: 2 }}>Effort</h1>
      </div>
      <div role="tablist" style={{ display: 'flex', gap: 24, borderBottom: '1px solid var(--border-subtle)' }}>
        {link('/effort/timesheet', 'Timesheet', tab === 'timesheet')}
        {link('/effort/analysis', 'Analysis', tab === 'analysis')}
      </div>
    </>
  );
}
