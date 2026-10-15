import { Link, useParams } from '@tanstack/react-router';
import type { ComponentType } from 'react';
import { isSettingsTab, SETTINGS_TABS, type SettingsTab } from '../search';
import { AlertsTab, ExtraDaysTab, HolidaysTab, HoursTab } from './CalendarTabs';
import { FieldMappingTab, StatusMappingTab } from './MappingTabs';
import { JiraTab } from './JiraTab';
import { MembersTab } from './MembersTab';
import { NameRulesTab } from './NameRulesTab';
import { StructureTab } from './StructureTab';

const PANELS: Record<SettingsTab, ComponentType> = {
  jira: JiraTab, 'field-mapping': FieldMappingTab, 'status-mapping': StatusMappingTab, structure: StructureTab, 'name-rules': NameRulesTab,
  members: MembersTab, holidays: HolidaysTab, hours: HoursTab, 'extra-days': ExtraDaysTab, alerts: AlertsTab,
};

export function SettingsPage() {
  const { tab: raw } = useParams({ strict: false }) as { tab: string };
  const tab: SettingsTab = isSettingsTab(raw) ? raw : 'jira';
  const Panel = PANELS[tab];
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, width: '100%' }}>
      <div>
        <div className="eyebrow">Admin</div>
        <h1 className="h1" style={{ marginTop: 2 }}>Settings</h1>
      </div>
      <div role="tablist" style={{ display: 'flex', gap: 24, borderBottom: '1px solid var(--border-subtle)', overflowX: 'auto' }}>
        {SETTINGS_TABS.map(([k, label]) => (
          <Link key={k} to="/settings/$tab" params={{ tab: k }} role="tab" aria-selected={k === tab} style={{
            padding: '10px 0', marginBottom: -1, whiteSpace: 'nowrap', textDecoration: 'none', font: 'var(--fw-semibold) var(--fs-small)/1.2 var(--font-text)',
            color: k === tab ? 'var(--text-heading)' : 'var(--text-muted)', borderBottom: `2px solid ${k === tab ? 'var(--color-accent)' : 'transparent'}`,
          }}>{label}</Link>
        ))}
      </div>
      <Panel />
    </div>
  );
}
