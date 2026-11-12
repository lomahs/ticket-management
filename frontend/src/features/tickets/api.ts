import { useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';
import { errorMessage } from '../../shared/api/errors';
import { logJiraRequests, logJiraResult } from '../../shared/api/jiraLog';
import type { Schemas } from '../../shared/api/types';
import { useToast } from '../../shared/state/toast';

export const boardQuery = (epic: string) => ({
  queryKey: ['tickets', 'board', epic] as const,
  queryFn: () => unwrap(http.GET('/api/v1/tickets/board', { params: { query: { anken: epic } } })),
});

/** The sub-task types of the anken's Jira project, read from Jira, and the one a new sub-task gets by default. */
export const subtaskTypesQuery = (epic: string) => ({
  queryKey: ['tickets', 'subtask-types', epic] as const,
  queryFn: () => unwrap(http.GET('/api/v1/tickets/subtask-types', { params: { query: { anken: epic } } })),
  staleTime: 10 * 60_000,
});

/** Reads every changed ticket from Jira and returns what the batch would change and create, with the requests. */
export function usePreviewBatch() {
  return useMutation({
    mutationKey: ['preview', 'tickets'],
    mutationFn: (body: Schemas['BatchIn']) => unwrap(http.POST('/api/v1/tickets/batch/preview', { body })),
  });
}

/** A reviewed batch to send: the review as shown and the new tickets it was made from. */
export interface BatchToApply { epic: string; preview: Schemas['BatchPreview']; creates: Schemas['NewTicketIn'][] }

/**
 * Applies a reviewed batch in the background: the requests go to the browser console, and the outcome is toasted even when the
 * screen was left meanwhile. A 409 means a ticket changed in Jira meanwhile and nothing was written.
 */
export function useApplyBatch() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationKey: ['write', 'tickets'],
    mutationFn: ({ epic, preview, creates }: BatchToApply) => unwrap(http.POST('/api/v1/tickets/batch/apply', { body: {
      epic,
      updates: preview.updates.map((u) => ({ key: u.key, changes: u.changes.map(({ field, before, after }) => ({ field, before, after })) })),
      creates,
    } })),
    onMutate: ({ epic, preview }) => logJiraRequests(`Commit ${epic}`, [...preview.creates.map((c) => c.request), ...preview.updates.flatMap((u) => u.requests)]),
    onSuccess: (r, { epic }) => {
      logJiraResult(`Commit ${epic}`, r);
      const made = Object.keys(r.created).length;
      toast(`${epic} is committed to Jira · ${[r.updated.length && `${r.updated.length} updated`, made && `${made} created`].filter(Boolean).join(', ')}`, 'success');
    },
    onError: (e, { epic }) => {
      logJiraResult(`Commit ${epic}`, e);
      toast(`${epic}: ${errorMessage(e)}`, true);
    },
    onSettled: () => qc.invalidateQueries(),
  });
}
