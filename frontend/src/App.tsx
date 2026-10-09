import { useState } from 'react';
import { ChatPanel } from './components/ChatPanel';
import { Sidebar } from './components/Sidebar';
import { TicketPanel } from './components/TicketPanel';
import { Button, Toast } from './ds';
import { CalendarScreen } from './screens/CalendarScreen';
import { EffortScreen } from './screens/EffortScreen';
import { MatrixScreen } from './screens/MatrixScreen';
import { MyWorkScreen } from './screens/MyWorkScreen';
import { OverviewScreen } from './screens/OverviewScreen';
import { PlanningScreen } from './screens/PlanningScreen';
import { SettingsScreen } from './screens/SettingsScreen';
import { useApp } from './state/AppContext';

export default function App() {
  const { screen, ticket, toast, closeToast } = useApp();
  const [chat, setChat] = useState(false);
  const fabRight = ticket ? 468 : 20;
  return (
    <div className="tt-root">
      <Sidebar />
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <main style={{ flex: 1, overflow: 'auto', padding: '20px 24px 40px' }}>
          {screen === 'matrix' && <MatrixScreen />}
          {screen === 'mywork' && <MyWorkScreen />}
          {screen === 'overview' && <OverviewScreen />}
          {screen === 'planning' && <PlanningScreen />}
          {screen === 'effort' && <EffortScreen />}
          {screen === 'calendar' && <CalendarScreen />}
          {screen === 'settings' && <SettingsScreen />}
        </main>
      </div>

      <TicketPanel />
      {chat && <ChatPanel right={fabRight} onClose={() => setChat(false)} />}

      <div style={{
        position: 'fixed', right: fabRight, bottom: 20, zIndex: 42, transition: 'right var(--duration-fast) var(--easing-standard)',
        filter: 'drop-shadow(0 6px 16px rgba(16,18,20,.22))',
      }}>
        <Button size="lg" iconBefore={chat ? 'x' : 'message-square'} onClick={() => setChat(!chat)}>{chat ? 'Close' : 'Ask'}</Button>
      </div>

      {toast && (
        <div style={{ position: 'fixed', left: '50%', bottom: 24, transform: 'translateX(-50%)', zIndex: 80 }}>
          <Toast key={toast.id} icon={toast.error ? 'circle-alert' : 'circle-check'} title={toast.msg} onClose={closeToast} />
        </div>
      )}
    </div>
  );
}
