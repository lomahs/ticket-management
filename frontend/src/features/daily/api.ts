import { keepPreviousData, useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';

/** @param date a captured day; undefined: today, live */
export const dailyQuery = (anken: string, date: string | undefined) => ({
  queryKey: ['daily', anken, date ?? null] as const,
  queryFn: () => unwrap(http.GET('/api/v1/daily', { params: { query: { anken, ...(date ? { date } : {}) } } })),
  placeholderData: keepPreviousData,
});

/** Captures today (replacing an earlier capture of today); every day of the anken is refetched, the list of days changed. */
export function useCapture(anken: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap(http.POST('/api/v1/daily/snapshots', { params: { query: { anken } } })),
    onSuccess: async () => { await qc.invalidateQueries({ queryKey: ['daily', anken] }); },
  });
}

/** A blank note removes it. */
export function useSaveNote(anken: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ key, note }: { key: string; note: string }) =>
      unwrap(http.PUT('/api/v1/daily/notes/{key}', { params: { path: { key } }, body: { note } })),
    onSuccess: async () => { await qc.invalidateQueries({ queryKey: ['daily', anken, null] }); },
  });
}
