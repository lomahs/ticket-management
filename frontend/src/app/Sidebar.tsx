import { useQuery } from '@tanstack/react-query';
import { Link, useRouterState } from '@tanstack/react-router';
import { useState } from 'react';
import { useLogout } from '../features/auth';
import { SyncButton } from '../features/sync';
import { pendingRequestsQuery } from '../features/users';
import { useMe, useMemberMap, useMeta } from '../shared/api/queries';
import { Icon, IconButton } from '../shared/ds';
import { ROLE_LABEL } from '../shared/format/labels';
import { visibleNav } from '../shared/nav';
import { Avatar, av } from '../shared/ui/bits';
import { AnkenSwitcher } from './AnkenSwitcher';

export function Sidebar({ theme, toggleTheme }: { theme: string; toggleTheme: () => void }) {
  const [open, setOpen] = useState(true);
  const path = useRouterState({ select: (s) => s.location.pathname });
  const meta = useMeta().data;
  const members = useMemberMap();
  const me = useMe().data;
  const logout = useLogout();
  const pending = useQuery({ ...pendingRequestsQuery, enabled: !!me?.admin, refetchInterval: 60_000 }).data?.length ?? 0;
  const meM = me?.member_id ? members[me.member_id] : undefined;
  const connection = !meta ? ' ' : meta.mode === 'demo' ? `Demo data · ${meta.projects.join(', ')}`
    : meta.projects.length ? `Connected to Jira · ${meta.projects.join(', ')}` : 'Jira not set up yet';
  return (
    <aside aria-label="Sidebar" style={{
      width: open ? 216 : 60, flex: 'none', transition: 'width var(--duration-fast) var(--easing-standard)', overflow: 'hidden',
      display: 'flex', flexDirection: 'column', background: 'var(--side-bg)', borderRight: '1px solid var(--border-subtle)',
    }}>
      <div style={{ height: 56, flex: 'none', display: 'flex', alignItems: 'center', gap: 6, padding: `0 10px 0 ${open ? 18 : 14}px`, borderBottom: '1px solid var(--border-subtle)' }}>
        {open && (
          <div style={{ flex: 1, minWidth: 0, whiteSpace: 'nowrap' }}>
            <div style={{ font: '700 17px/1.1 var(--font-display)', color: 'var(--text-heading)' }}>Team Tracker</div>
            <div className="ellipsis" style={{ fontSize: 11, color: 'var(--text-muted)' }}>{connection}</div>
          </div>
        )}
        <IconButton icon={open ? 'panel-left-close' : 'panel-left-open'} label={open ? 'Hide sidebar' : 'Show sidebar'} size="sm" onClick={() => setOpen(!open)} />
      </div>
      <div style={{ padding: open ? '10px 10px 0' : 0 }}>
        <AnkenSwitcher compact={!open} />
      </div>
      <nav aria-label="Main" style={{ display: 'flex', flexDirection: 'column', gap: 2, padding: '12px 10px', flex: 1 }}>
        {visibleNav(me).map((n) => {
          const on = n.active(path);
          return (
            <Link key={n.to} to={n.to} title={n.label} className="hv-bg" aria-current={on ? 'page' : undefined} style={{
              display: 'flex', alignItems: 'center', justifyContent: open ? 'flex-start' : 'center', gap: 10, height: 34, padding: '0 10px',
              borderRadius: 10, background: on ? 'var(--surface-tint)' : 'transparent', textDecoration: 'none',
              color: on ? 'var(--text-heading)' : 'var(--text-body)', font: `${on ? 600 : 500} 13px/1 var(--font-text)`,
            }}>
              <Icon name={n.icon} size={16} />
              {open && <span style={{ flex: 1, whiteSpace: 'nowrap' }}>{n.label}</span>}
              {n.to === '/admin/users' && pending > 0 && <span className="count" style={{ background: 'var(--surface-tint)', color: 'var(--text-heading)' }}>{pending}</span>}
            </Link>
          );
        })}
      </nav>
      <div style={{ padding: '8px 10px', borderTop: '1px solid var(--border-subtle)' }}>
        <SyncButton compact={!open} />
      </div>
      <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', justifyContent: 'center', gap: 10, padding: '12px 14px', borderTop: '1px solid var(--border-subtle)' }}>
        <Avatar a={av(meM, me?.name ?? '')} size={28} />
        {open && (
          <>
            <div style={{ minWidth: 0, whiteSpace: 'nowrap' }}>
              <div style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{me?.name}</div>
              <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{me?.role ? ROLE_LABEL[me.role] : 'No role'}</div>
            </div>
            <span className="spacer" />
          </>
        )}
        <IconButton icon="log-out" label="Sign out" size="sm" onClick={() => logout.mutate()} />
        <IconButton icon={theme === 'dark' ? 'sun' : 'moon'} label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'} size="sm" onClick={toggleTheme} />
      </div>
    </aside>
  );
}
