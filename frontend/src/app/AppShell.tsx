import { Link, Outlet, useMatches } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { ChatPanel } from '../features/qa';
import { TicketPanel } from '../features/ticket';
import { can, useMe } from '../shared/api/queries';
import { Button } from '../shared/ds';
import { homeOf } from '../shared/nav';
import { systemTheme, usePref } from '../shared/state/prefs';
import { useTicketKey } from '../shared/state/ticket';
import { Sidebar } from './Sidebar';

export function AppShell() {
  const [theme, setTheme] = usePref('theme', systemTheme());
  const [chat, setChat] = useState(false);
  const ticket = useTicketKey();
  const me = useMe().data;
  // The deepest screen's feature (staticData.feature); without read on it the screen is not rendered at all.
  const feature = useMatches({ select: (ms) => ms.findLast((m) => m.staticData?.feature)?.staticData.feature });
  const allowed = !feature || can(me, feature, 'read');
  useEffect(() => { document.documentElement.setAttribute('data-theme', theme); }, [theme]);
  const fabRight = ticket ? 468 : 20;
  return (
    <div className="tt-root">
      <Sidebar theme={theme} toggleTheme={() => setTheme(theme === 'dark' ? 'light' : 'dark')} />
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <main style={{ flex: 1, overflow: 'auto', padding: '20px 24px 40px' }}>
          {allowed ? <Outlet /> : <NoScreenAccess />}
        </main>
      </div>
      <TicketPanel />
      {chat && <ChatPanel right={fabRight} onClose={() => setChat(false)} />}
      <div style={{ position: 'fixed', right: fabRight, bottom: 20, zIndex: 42, transition: 'right var(--duration-fast) var(--easing-standard)', filter: 'drop-shadow(0 6px 16px rgba(16,18,20,.22))' }}>
        <Button size="lg" iconBefore={chat ? 'x' : 'message-square'} onClick={() => setChat(!chat)}>{chat ? 'Close' : 'Ask'}</Button>
      </div>
    </div>
  );
}

function NoScreenAccess() {
  const home = homeOf(useMe().data);
  return (
    <div className="card" role="alert" style={{ padding: 24 }}>
      <div className="title">You don't have access to this screen</div>
      <p className="eyebrow">Ask the admin if you need it.{home && <> <Link to={home}>Go to your first screen</Link></>}</p>
    </div>
  );
}

export function NotFound() {
  return (
    <div className="card" style={{ padding: 24 }}>
      <div className="title">Page not found</div>
      <p className="eyebrow">This address does not match a Team Tracker screen. <Link to="/my-work">Go to My Work</Link></p>
    </div>
  );
}
