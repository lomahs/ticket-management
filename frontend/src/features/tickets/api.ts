import { useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';
import type { Schemas } from '../../shared/api/types';

export const boardQuery = (epic: string) => ({
  queryKey: ['tickets', 'board', epic] as const,
  queryFn: () => unwrap(http.GET('/api/v1/tickets/board', { params: { query: { anken: epic } } })),
});

/** Reads every changed ticket from Jira and returns what the batch would change and create, with the requests. */
export function usePreviewBatch() {
  return useMutation({
    mutationKey: ['preview', 'tickets'],
    mutationFn: (body: Schemas['BatchIn']) => unwrap(http.POST('/api/v1/tickets/batch/preview', { body })),
  });
}

/** Applies a reviewed batch; a 409 means a ticket changed in Jira meanwhile and nothing was written. */
export function useApplyBatch() {
  const qc = useQueryClient();
  return useMutation({
    mutationKey: ['write', 'tickets'],
    mutationFn: (body: Schemas['BatchApplyIn']) => unwrap(http.POST('/api/v1/tickets/batch/apply', { body })),
    onSettled: () => qc.invalidateQueries(),
  });
}
