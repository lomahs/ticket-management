import { keepPreviousData, useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../../shared/api/client';
import type { Schemas } from '../../../shared/api/types';

export type ReplanBody = Schemas['ReplanBody'];
export type ReplanApplyIn = Schemas['ReplanApplyIn'];

/** The phase's sub-tasks with the dates and assignee they would get; a read, though it is a POST. */
export const replanQuery = (epic: string, body: ReplanBody) => ({
  queryKey: ['planning', 'replan', epic, body] as const,
  queryFn: () => unwrap(http.POST('/api/v1/planning/{epic}/replan', { params: { path: { epic } }, body })),
  placeholderData: keepPreviousData,
});

export const useReplanPreview = (epic: string) => useMutation({
  mutationFn: (body: ReplanBody) => unwrap(http.POST('/api/v1/planning/{epic}/replan/preview', { params: { path: { epic } }, body })),
});

export function useApplyReplan(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationKey: ['write', 'replan'],
    mutationFn: (body: ReplanApplyIn) => unwrap(http.POST('/api/v1/planning/{epic}/replan/apply', { params: { path: { epic } }, body })),
    onSettled: () => qc.invalidateQueries(),
  });
}
