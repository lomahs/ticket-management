import { useState } from 'react';
import { Icon, IconButton, type IconName } from '../ds';
import { av } from '../lib/domain';
import { useApp, type Screen } from '../state/AppContext';
import { Avatar } from './bits';

const NAV: [Screen, string, IconName][] = [
  ['mywork', 'My Work', 'list-todo'], ['matrix', 'Function Matrix', 'grid-3x3'], ['overview', 'Anken Overview', 'layout-dashboard'],
  ['planning', 'Planning', 'chart-gantt'], ['effort', 'Effort', 'timer'], ['calendar', 'Team Calendar', 'calendar-days'],
  ['settings', 'Settings', 'settings'],
];

export function Sidebar() {
  const { snap, screen, go, me, theme, toggleTheme } = useApp();
  const [open, setOpen] = useState(true);
  const meAv = av(snap, me);
  const project = snap.settings.jira.epics[0]?.split('-')[0] ?? 'PAY';
  return (
    <aside
      style={{
        width: open ? 216 : 60, flex: 'none', transition: 'width var(--duration-fast) var(--easing-standard)', overflow: 'hidden',
        display: 'flex', flexDirection: 'column', background: 'var(--side-bg)', borderRight: '1px solid var(--border-subtle)',
      }}
    >
      <div style={{ height: 56, flex: 'none', display: 'flex', alignItems: 'center', gap: 6, padding: `0 10px 0 ${open ? 18 : 14}px`, borderBottom: '1px solid var(--border-subtle)' }}>
        {open && (
          <div style={{ flex: 1, minWidth: 0, whiteSpace: 'nowrap' }}>
            <div style={{ font: '700 17px/1.1 var(--font-display)', color: 'var(--text-heading)' }}>Team Tracker</div>
            <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>Connected to Jira · {project}</div>
          </div>
        )}
        <IconButton icon={open ? 'panel-left-close' : 'panel-left-open'} label={open ? 'Hide sidebar' : 'Show sidebar'} size="sm" onClick={() => setOpen(!open)} />
      </div>
      <nav style={{ display: 'flex', flexDirection: 'column', gap: 2, padding: '12px 10px', flex: 1 }}>
        {NAV.map(([k, label, icon]) => {
          const on = screen === k;
          return (
            <button
              key={k}
              className="hv-bg"
              onClick={() => go(k)}
              title={label}
              aria-current={on ? 'page' : undefined}
              style={{
                display: 'flex', alignItems: 'center', justifyContent: open ? 'flex-start' : 'center', gap: 10, height: 34, padding: '0 10px',
                border: 0, borderRadius: 10, background: on ? 'var(--surface-tint)' : 'transparent',
                color: on ? 'var(--text-heading)' : 'var(--text-body)', font: `${on ? 600 : 500} 13px/1 var(--font-text)`, cursor: 'pointer', textAlign: 'left',
              }}
            >
              <Icon name={icon} size={16} />
              {open && <span style={{ flex: 1, whiteSpace: 'nowrap' }}>{label}</span>}
            </button>
          );
        })}
      </nav>
      <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', justifyContent: 'center', gap: 10, padding: '12px 14px', borderTop: '1px solid var(--border-subtle)' }}>
        <Avatar a={meAv} size={28} />
        {open && (
          <>
            <div style={{ minWidth: 0, whiteSpace: 'nowrap' }}>
              <div style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{meAv.name}</div>
              <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{meAv.roleLabel}</div>
            </div>
            <span className="spacer" />
          </>
        )}
        <IconButton icon={theme === 'dark' ? 'sun' : 'moon'} label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'} size="sm" onClick={toggleTheme} />
      </div>
    </aside>
  );
}
