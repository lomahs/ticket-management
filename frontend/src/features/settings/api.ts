import { keepPreviousData, useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';
import type { Schemas } from '../../shared/api/types';

export const jiraQuery = { queryKey: ['settings', 'jira'] as const, queryFn: () => unwrap(http.GET('/api/v1/settings/jira')) };
export const fieldMappingQuery = { queryKey: ['settings', 'field-mapping'] as const, queryFn: () => unwrap(http.GET('/api/v1/settings/field-mapping')) };
export const statusMappingQuery = { queryKey: ['settings', 'status-mapping'] as const, queryFn: () => unwrap(http.GET('/api/v1/settings/status-mapping')) };
export const nameRulesQuery = { queryKey: ['settings', 'name-rules'] as const, queryFn: () => unwrap(http.GET('/api/v1/settings/name-rules')) };
export const holidaysQuery = { queryKey: ['settings', 'holidays'] as const, queryFn: () => unwrap(http.GET('/api/v1/holidays')) };
export const extraDaysQuery = { queryKey: ['settings', 'extra-days'] as const, queryFn: () => unwrap(http.GET('/api/v1/extra-days')) };
export const hoursQuery = { queryKey: ['settings', 'hours'] as const, queryFn: () => unwrap(http.GET('/api/v1/settings/working-hours')) };
export const alertsQuery = { queryKey: ['settings', 'alerts'] as const, queryFn: () => unwrap(http.GET('/api/v1/settings/alerts')) };
export const structureQuery = { queryKey: ['settings', 'structure'] as const, queryFn: () => unwrap(http.GET('/api/v1/settings/structure')) };

export const namePreviewQuery = (body: Schemas['NameRulePreviewIn']) => ({
  queryKey: ['settings', 'name-preview', body] as const,
  queryFn: () => unwrap(http.POST('/api/v1/settings/name-rules/preview', { body })),
  placeholderData: keepPreviousData,
});

export const structurePreviewQuery = (body: Schemas['StructureIn']) => ({
  queryKey: ['settings', 'structure-preview', body] as const,
  queryFn: () => unwrap(http.POST('/api/v1/settings/structure/preview', { body })),
  placeholderData: keepPreviousData,
});

/** Settings writes can re-sync or change derived data everywhere, so they refresh every screen. */
function useSettingsWrite<T, R>(fn: (v: T) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries() });
}

export const useSaveJira = () => useSettingsWrite((b: Schemas['JiraIn']) => unwrap(http.PUT('/api/v1/settings/jira', { body: b })));
export const useTestJira = () => useMutation({ mutationFn: (b: Schemas['JiraTestIn']) => unwrap(http.POST('/api/v1/settings/jira/test', { body: b })) });
export const useAddEpic = () => useSettingsWrite((key: string) => unwrap(http.POST('/api/v1/settings/epics', { body: { key } })));
export const useRemoveEpic = () => useSettingsWrite((key: string) => unwrap(http.DELETE('/api/v1/settings/epics/{key}', { params: { path: { key } } })));
export const useSaveFieldMapping = () => useSettingsWrite((b: Schemas['FieldMappingIn']) => unwrap(http.PUT('/api/v1/settings/field-mapping', { body: b })));
export const useCheckFieldMapping = () => useMutation({ mutationFn: (b: Schemas['FieldCheckIn']) => unwrap(http.POST('/api/v1/settings/field-mapping/check', { body: b })) });
export const useSaveStatusMapping = () => useSettingsWrite((mapping: Schemas['StatusMappingIn']['mapping']) => unwrap(http.PUT('/api/v1/settings/status-mapping', { body: { mapping } })));
export const useSaveStructure = () => useSettingsWrite((b: Schemas['StructureIn']) => unwrap(http.PUT('/api/v1/settings/structure', { body: b })));
export const useSaveNameRules = () => useSettingsWrite((b: Schemas['NameRulesIn']) => unwrap(http.PUT('/api/v1/settings/name-rules', { body: b })));
export const usePatchMember = () => useSettingsWrite((v: { id: string; body: Schemas['MemberPatchIn'] }) =>
  unwrap(http.PATCH('/api/v1/members/{member_id}', { params: { path: { member_id: v.id } }, body: v.body })));
export const useAddHoliday = () => useSettingsWrite((b: Schemas['HolidayIn']) => unwrap(http.POST('/api/v1/holidays', { body: b })));
export const useDeleteHoliday = () => useSettingsWrite((date: string) => unwrap(http.DELETE('/api/v1/holidays/{date}', { params: { path: { date } } })));
export const useAddExtraDay = () => useSettingsWrite((b: Schemas['ExtraDayIn']) => unwrap(http.POST('/api/v1/extra-days', { body: b })));
export const useDeleteExtraDay = () => useSettingsWrite((date: string) => unwrap(http.DELETE('/api/v1/extra-days/{date}', { params: { path: { date } } })));
export const useSaveHours = () => useSettingsWrite((b: Schemas['WorkingHoursIn']) => unwrap(http.PUT('/api/v1/settings/working-hours', { body: b })));
export const useSaveAlerts = () => useSettingsWrite((b: Schemas['AlertsIn']) => unwrap(http.PUT('/api/v1/settings/alerts', { body: b })));
