import { useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../../shared/api/client';
import type { Schemas } from '../../../shared/api/types';

export type EstimationRow = Schemas['EstimationRow'];
export type ImportRow = Schemas['EstimationImportRow'];

export const estimationQuery = (epic: string) => ({
  queryKey: ['planning', 'estimation', epic] as const,
  queryFn: () => unwrap(http.GET('/api/v1/planning/{epic}/estimation', { params: { path: { epic } } })),
});

export function useSaveEstimation(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (rows: EstimationRow[]) => unwrap(http.PUT('/api/v1/planning/{epic}/estimation', { params: { path: { epic } }, body: { rows } })),
    // the saved rows come back; the screens built on the estimation (schedule, plan) read it again
    onSuccess: (v) => {
      qc.setQueryData(estimationQuery(epic).queryKey, v);
      void qc.invalidateQueries({ queryKey: ['planning'], predicate: (q) => q.queryKey[1] !== 'estimation' });
    },
  });
}

/** Reads a filled template; nothing is saved. */
export const useImportEstimation = (epic: string) => useMutation({
  mutationFn: (file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return unwrap(http.POST('/api/v1/planning/{epic}/estimation/import', { params: { path: { epic } }, body: fd as never, bodySerializer: (b) => b as FormData }));
  },
});

export const ESTIMATION_TEMPLATE_URL = '/api/v1/planning/estimation/template';
