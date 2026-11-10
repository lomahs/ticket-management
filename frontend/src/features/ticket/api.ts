import { useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';
import { errorMessage } from '../../shared/api/errors';
import { logJiraRequests, logJiraResult } from '../../shared/api/jiraLog';
import type { Schemas } from '../../shared/api/types';
import { useToast } from '../../shared/state/toast';

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

/**
 * Applies a review's changes as they were shown, in the background: the requests go to the browser console, and the outcome
 * is toasted even when the panel was closed meanwhile. A 409 means Jira changed meanwhile and nothing was written.
 */
export function useApplyTicket(key: string) {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationKey: ['write', 'ticket', key],
    mutationFn: (review: Schemas['TicketChangePreview']) => unwrap(http.PATCH('/api/v1/tickets/{key}', {
      params: { path: { key } }, body: { changes: review.changes.map(({ field, before, after }) => ({ field, before, after })) },
    })),
    onMutate: (review) => logJiraRequests(`Apply ${key}`, review.requests),
    onSuccess: (detail, review) => {
      logJiraResult(`Apply ${key}`, detail);
      const n = review.changes.length;
      toast(`${key} is updated in Jira · ${n} field${n === 1 ? '' : 's'} written`, 'success');
    },
    onError: (e) => {
      logJiraResult(`Apply ${key}`, e);
      toast(`${key}: ${errorMessage(e)}`, true);
    },
    onSettled: () => qc.invalidateQueries(),
  });
}
