import { keepPreviousData, useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../../shared/api/client';
import type { Schemas } from '../../../shared/api/types';

export type PhaseIn = Schemas['PhaseIn'];
export type PhaseRow = Schemas['PhaseRow'];

export const masterScheduleQuery = (epic: string) => ({
  queryKey: ['planning', 'master-schedule', epic] as const,
  queryFn: () => unwrap(http.GET('/api/v1/planning/{epic}/master-schedule', { params: { path: { epic } } })),
});

/** End, plan MD and effort of the complete rows, as they would be saved. */
export const schedulePreviewQuery = (epic: string, phases: PhaseIn[]) => ({
  queryKey: ['planning', 'master-schedule-preview', epic, phases] as const,
  queryFn: () => unwrap(http.POST('/api/v1/planning/{epic}/master-schedule/preview', { params: { path: { epic } }, body: { phases } })),
  enabled: phases.length > 0,
  placeholderData: keepPreviousData,
  retry: false,
});

export function useSaveMasterSchedule(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (b: { phases: PhaseIn[]; reason: string }) =>
      unwrap(http.POST('/api/v1/planning/{epic}/master-schedule', { params: { path: { epic } }, body: b })),
    onSuccess: (v) => {
      qc.setQueryData(masterScheduleQuery(epic).queryKey, v);
      void qc.invalidateQueries({ queryKey: ['planning'], predicate: (q) => q.queryKey[1] !== 'master-schedule' });
    },
  });
}
