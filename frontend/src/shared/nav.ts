import type { IconName } from './ds';
import type { Feature, Me } from './api/types';
import { can } from './api/queries';

export interface NavItem { to: string; label: string; icon: IconName; feature?: Feature; admin?: boolean; active: (p: string) => boolean }

/** The sidebar, in order. A screen shows when the user may read its feature; Onboarding, Users and Settings only for an admin. */
export const NAV: NavItem[] = [
  { to: '/my-work', label: 'My Work', icon: 'list-todo', feature: 'my_work', active: (p) => p.startsWith('/my-work') },
  { to: '/matrix', label: 'Function Matrix', icon: 'grid-3x3', feature: 'matrix', active: (p) => /^\/anken\/[^/]+\/matrix/.test(p) },
  { to: '/tickets', label: 'Tickets', icon: 'list-todo', feature: 'ticket', active: (p) => p.startsWith('/tickets') },
  { to: '/anken', label: 'Anken Overview', icon: 'layout-dashboard', feature: 'overview', active: (p) => p.startsWith('/anken') && !/\/matrix/.test(p) },
  { to: '/daily', label: 'Daily Overview', icon: 'camera', feature: 'overview', active: (p) => p.startsWith('/daily') },
  { to: '/ticket-check', label: 'Ticket Check', icon: 'scan-search', feature: 'overview', active: (p) => p.startsWith('/ticket-check') },
  { to: '/planning', label: 'Planning', icon: 'chart-gantt', feature: 'planning', active: (p) => p.startsWith('/planning') },
  { to: '/onboarding', label: 'Onboarding', icon: 'sunrise', admin: true, active: (p) => p.startsWith('/onboarding') },
  { to: '/effort/timesheet', label: 'Effort', icon: 'timer', feature: 'effort', active: (p) => p.startsWith('/effort') },
  { to: '/calendar', label: 'Team Calendar', icon: 'calendar-days', feature: 'calendar', active: (p) => p.startsWith('/calendar') },
  { to: '/admin/users', label: 'Users', icon: 'users', admin: true, active: (p) => p.startsWith('/admin/users') },
  { to: '/settings/jira', label: 'Settings', icon: 'settings', admin: true, active: (p) => p.startsWith('/settings') },
];

export function visibleNav(me: Me | undefined): NavItem[] {
  return NAV.filter((n) => (n.admin ? !!me?.admin : !n.feature || can(me, n.feature, 'read')));
}

/** The first screen the user may read (sidebar order), or null when there is none. */
export function homeOf(me: Me | undefined): string | null {
  return NAV.find((n) => !n.admin && n.feature && can(me, n.feature, 'read'))?.to ?? null;
}
