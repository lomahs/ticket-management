import { keepPreviousData, useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';
import type { Schemas } from '../../shared/api/types';
import { ankenParam } from '../../shared/state/anken';

export const jiraQuery = { queryKey: ['settings', 'jira'] as const, queryFn: () => unwrap(http.GET('/api/v1/settings/jira')) };
export const dailyCaptureQuery = { queryKey: ['settings', 'daily-capture'] as const, queryFn: () => unwrap(http.GET('/api/v1/settings/daily-capture')) };
export const fieldMappingQuery = { queryKey: ['settings', 'field-mapping'] as const, queryFn: () => unwrap(http.GET('/api/v1/settings/field-mapping')) };
export const statusMappingQuery = { queryKey: ['settings', 'status-mapping'] as const, queryFn: () => unwrap(http.GET('/api/v1/settings/status-mapping')) };
/** `anken` null: the default (the template for new anken). */
export const nameRulesQuery = (anken: string | null) => ({
  queryKey: ['settings', 'name-rules', anken] as const,
  queryFn: () => unwrap(http.GET('/api/v1/settings/name-rules', { params: { query: ankenParam(anken) } })),
});
/** `anken` null: the default weights. */
export const stepWeightsQuery = (anken: string | null) => ({
  queryKey: ['settings', 'step-weights', anken] as const,
  queryFn: () => unwrap(http.GET('/api/v1/settings/step-weights', { params: { query: ankenParam(anken) } })),
});
export const holidaysQuery = { queryKey: ['settings', 'holidays'] as const, queryFn: () => unwrap(http.GET('/api/v1/holidays')) };
export const extraDaysQuery = { queryKey: ['settings', 'extra-days'] as const, queryFn: () => unwrap(http.GET('/api/v1/extra-days')) };
export const hoursQuery = { queryKey: ['settings', 'hours'] as const, queryFn: () => unwrap(http.GET('/api/v1/settings/working-hours')) };
/** `failed`: only the requests Jira refused or that were not sent. */
export const jiraLogQuery = (failed: boolean) => ({
  queryKey: ['admin', 'jira-log', failed] as const,
  queryFn: () => unwrap(http.GET('/api/v1/admin/jira-log', { params: { query: { failed } } })),
});
export const alertsQuery = { queryKey: ['settings', 'alerts'] as const, queryFn: () => unwrap(http.GET('/api/v1/settings/alerts')) };
export const structureQuery = (anken: string | null) => ({
  queryKey: ['settings', 'structure', anken] as const,
  queryFn: () => unwrap(http.GET('/api/v1/settings/structure', { params: { query: ankenParam(anken) } })),
});

export const namePreviewQuery = (anken: string | null, body: Schemas['NameRulePreviewIn']) => ({
  queryKey: ['settings', 'name-preview', anken, body] as const,
  queryFn: () => unwrap(http.POST('/api/v1/settings/name-rules/preview', { body, params: { query: ankenParam(anken) } })),
  placeholderData: keepPreviousData,
});

export const structurePreviewQuery = (anken: string | null, body: Schemas['StructureIn']) => ({
  queryKey: ['settings', 'structure-preview', anken, body] as const,
  queryFn: () => unwrap(http.POST('/api/v1/settings/structure/preview', { body, params: { query: ankenParam(anken) } })),
  placeholderData: keepPreviousData,
});

/** Settings writes can re-sync or change derived data everywhere, so they refresh every screen. */
function useSettingsWrite<T, R>(fn: (v: T) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries() });
}

export const useSaveJira = () => useSettingsWrite((b: Schemas['JiraIn']) => unwrap(http.PUT('/api/v1/settings/jira', { body: b })));
export const useSaveDailyCapture = () => useSettingsWrite((time: string) => unwrap(http.PUT('/api/v1/settings/daily-capture', { body: { time } })));
export const useTestJira = () => useMutation({ mutationFn: (b: Schemas['JiraTestIn']) => unwrap(http.POST('/api/v1/settings/jira/test', { body: b })) });
export const useAddEpic = () => useSettingsWrite((key: string) => unwrap(http.POST('/api/v1/settings/epics', { body: { key } })));
export const useRemoveEpic = () => useSettingsWrite((key: string) => unwrap(http.DELETE('/api/v1/settings/epics/{key}', { params: { path: { key } } })));
export const useSaveFieldMapping = () => useSettingsWrite((b: Schemas['FieldMappingIn']) => unwrap(http.PUT('/api/v1/settings/field-mapping', { body: b })));
export const useCheckFieldMapping = () => useMutation({ mutationFn: (b: Schemas['FieldCheckIn']) => unwrap(http.POST('/api/v1/settings/field-mapping/check', { body: b })) });
export const useSaveStatusMapping = () => useSettingsWrite((mapping: Schemas['StatusMappingIn']['mapping']) => unwrap(http.PUT('/api/v1/settings/status-mapping', { body: { mapping } })));
export const useSaveStructure = (anken: string | null) => useSettingsWrite((b: Schemas['StructureIn']) =>
  unwrap(http.PUT('/api/v1/settings/structure', { body: b, params: { query: ankenParam(anken) } })));
export const useSaveNameRules = (anken: string | null) => useSettingsWrite((b: Schemas['NameRulesIn']) =>
  unwrap(http.PUT('/api/v1/settings/name-rules', { body: b, params: { query: ankenParam(anken) } })));
export const useSaveStepWeights = (anken: string | null) => useSettingsWrite((weights: Record<string, number>) =>
  unwrap(http.PUT('/api/v1/settings/step-weights', { body: { weights }, params: { query: ankenParam(anken) } })));
export const useResetStepWeights = (anken: string) => useSettingsWrite(() =>
  unwrap(http.DELETE('/api/v1/settings/step-weights', { params: { query: { anken } } })));
/** Copies another anken's (or the default's) structure and name rules into `epic`; runs a full sync. */
export const useCopyAnkenSettings = (epic: string) => useSettingsWrite((from: string) =>
  unwrap(http.POST('/api/v1/settings/anken/{epic}/copy', { params: { path: { epic } }, body: { from } })));
export const useAddHoliday = () => useSettingsWrite((b: Schemas['HolidayIn']) => unwrap(http.POST('/api/v1/holidays', { body: b })));
export const useDeleteHoliday = () => useSettingsWrite((date: string) => unwrap(http.DELETE('/api/v1/holidays/{date}', { params: { path: { date } } })));
export const useAddExtraDay = () => useSettingsWrite((b: Schemas['ExtraDayIn']) => unwrap(http.POST('/api/v1/extra-days', { body: b })));
export const useDeleteExtraDay = () => useSettingsWrite((date: string) => unwrap(http.DELETE('/api/v1/extra-days/{date}', { params: { path: { date } } })));
export const useSaveHours = () => useSettingsWrite((b: Schemas['WorkingHoursIn']) => unwrap(http.PUT('/api/v1/settings/working-hours', { body: b })));
export const useSaveAlerts = () => useSettingsWrite((b: Schemas['AlertsIn']) => unwrap(http.PUT('/api/v1/settings/alerts', { body: b })));
