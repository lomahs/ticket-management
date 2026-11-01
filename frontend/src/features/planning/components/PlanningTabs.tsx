import { Link } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import { useAnkenScope } from '../../../shared/state/anken';

const TABS = [
  ['/planning', 'Gantt'], ['/planning/estimation', 'Estimation'], ['/planning/schedule', 'Master schedule'],
  ['/planning/detail', 'Detail plan'], ['/planning/replan', 'Re-plan'],
] as const;
export type PlanningPath = (typeof TABS)[number][0];

/** The tabs of Planning: the Gantt and the steps that build an anken's plan. */
export function PlanningTabs({ at }: { at: PlanningPath }) {
  return (
    <div role="tablist" aria-label="Planning" style={{ display: 'flex', gap: 24, borderBottom: '1px solid var(--border-subtle)', overflowX: 'auto' }}>
      {TABS.map(([to, label]) => (
        <Link key={to} to={to} search={(p: { anken?: string }) => ({ anken: p.anken })} role="tab" aria-selected={to === at} style={{
          padding: '10px 0', marginBottom: -1, whiteSpace: 'nowrap', textDecoration: 'none', font: 'var(--fw-semibold) var(--fs-small)/1.2 var(--font-text)',
          color: to === at ? 'var(--text-heading)' : 'var(--text-muted)', borderBottom: `2px solid ${to === at ? 'var(--color-accent)' : 'transparent'}`,
        }}>{label}</Link>
      ))}
    </div>
  );
}

/** The anken a plan-building screen works on; without one it asks for it. */
export function WithAnken({ children }: { children: (anken: string) => ReactNode }) {
  const { anken } = useAnkenScope();
  if (!anken) return <div className="card" style={{ padding: 20, color: 'var(--text-muted)' }}>Pick an anken in the switcher to plan it.</div>;
  return <>{children(anken)}</>;
}
