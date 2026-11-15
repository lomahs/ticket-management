import { Link, Outlet, useMatches, useParams } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { ChatPanel } from '../features/qa';
import { TicketPanel } from '../features/ticket';
import { can, useDraftAnken, useMe } from '../shared/api/queries';
import { Button } from '../shared/ds';
import { homeOf } from '../shared/nav';
import { isDraftKey, useAnkenScope } from '../shared/state/anken';
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
  // a screen of the anken's Jira data has nothing to show for a draft anken (picked in the switcher, or in the URL)
  const ankenScreen = useMatches({ select: (ms) => ms.some((m) => m.staticData?.ankenScreen) });
  const { anken } = useAnkenScope();
  const epic = useParams({ strict: false, select: (p) => (p as { epic?: string }).epic });
  const key = epic ?? anken;
  const draft = ankenScreen && isDraftKey(key) ? key : null;
  useEffect(() => { document.documentElement.setAttribute('data-theme', theme); }, [theme]);
  const fabRight = ticket ? 468 : 20;
  return (
    <div className="tt-root">
      <Sidebar theme={theme} toggleTheme={() => setTheme(theme === 'dark' ? 'light' : 'dark')} />
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <main style={{ flex: 1, overflow: 'auto', padding: '20px 24px 40px' }}>
          {!allowed ? <NoScreenAccess /> : draft ? <DraftNotice draft={draft} /> : <Outlet />}
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

/** A draft anken picked on a screen of Jira data: it has no epic, so only Planning's plan screens show it. */
function DraftNotice({ draft }: { draft: string }) {
  const title = useDraftAnken().find((a) => a.key === draft)?.title || draft;
  return (
    <div className="card" role="status" style={{ padding: 24 }}>
      <div className="title">{`${title} has no epic yet`}</div>
      <p className="eyebrow">
        {'This screen shows what is in Jira. Plan the anken in '}
        <Link to="/planning/schedule" search={{ anken: draft }}>Master schedule</Link>{' or '}
        <Link to="/planning/detail" search={{ anken: draft }}>Detail plan</Link>{', and link its epic in Onboarding.'}
      </p>
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
