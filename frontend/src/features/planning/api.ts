import { keepPreviousData, useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';
import type { Schemas } from '../../shared/api/types';
import { ankenParam } from '../../shared/state/anken';

export const planningQuery = (start: string, weeks: number, anken: string | null) => ({
  queryKey: ['planning', start, weeks, anken] as const,
  queryFn: () => unwrap(http.GET('/api/v1/planning', { params: { query: { start, weeks, ...ankenParam(anken) } } })),
  placeholderData: keepPreviousData,
});

export const planningPreviewQuery = (start: string, changes: Schemas['PlanChangeIn'][], anken: string | null) => ({
  queryKey: ['planning-preview', start, changes, anken] as const,
  queryFn: () => unwrap(http.POST('/api/v1/planning/preview', { params: { query: ankenParam(anken) }, body: { start, changes } })),
  enabled: changes.length > 0,
  placeholderData: keepPreviousData,
  retry: false,
});

/** The five steps of the function that sub-task `key` belongs to. */
export const functionOfQuery = (key: string) => ({
  queryKey: ['function-of', key] as const,
  queryFn: () => unwrap(http.GET('/api/v1/tickets/{key}/function', { params: { path: { key } } })),
  enabled: !!key,
});

export function usePushPlan() {
  const qc = useQueryClient();
  return useMutation({
    mutationKey: ['write', 'plan'],
    mutationFn: (changes: Schemas['PlanChangeIn'][]) => unwrap(http.POST('/api/v1/planning/push', { body: { changes } })),
    onSuccess: () => qc.invalidateQueries(),
  });
}
