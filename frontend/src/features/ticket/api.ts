import { useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';
import type { Schemas } from '../../shared/api/types';

export const ticketQuery = (key: string) => ({
  queryKey: ['ticket', key] as const,
  queryFn: () => unwrap(http.GET('/api/v1/tickets/{key}', { params: { path: { key } } })),
});

/** Reads the ticket from Jira and returns what the draft would change, and the requests that would be sent. */
export function usePreviewTicket(key: string) {
  return useMutation({
    mutationKey: ['preview', 'ticket', key],
    mutationFn: (body: Schemas['TicketChangesIn']) =>
      unwrap(http.POST('/api/v1/tickets/{key}/changes/preview', { params: { path: { key } }, body })),
  });
}

/** Applies a review's changes as they were shown; a 409 means Jira changed meanwhile and nothing was written. */
export function useApplyTicket(key: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationKey: ['write', 'ticket', key],
    mutationFn: (changes: Schemas['FieldChange'][]) => unwrap(http.PATCH('/api/v1/tickets/{key}', {
      params: { path: { key } }, body: { changes: changes.map(({ field, before, after }) => ({ field, before, after })) },
    })),
    onSettled: () => qc.invalidateQueries(),
  });
}
