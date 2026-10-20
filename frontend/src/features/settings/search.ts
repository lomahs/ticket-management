export const SETTINGS_TABS = [
  ['jira', 'Jira'], ['field-mapping', 'Field mapping'], ['status-mapping', 'Status mapping'], ['structure', 'Phases & tasks'],
  ['name-rules', 'Name rules'], ['holidays', 'Holidays'], ['hours', 'Hours'], ['extra-days', 'Extra working days'], ['alerts', 'Alerts'],
] as const;
export type SettingsTab = (typeof SETTINGS_TABS)[number][0];
export const isSettingsTab = (t: string): t is SettingsTab => SETTINGS_TABS.some(([k]) => k === t);
