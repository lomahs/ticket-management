import { useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../../shared/api/client';
import type { Schemas } from '../../../shared/api/types';

export type DetailPlanView = Schemas['DetailPlanView'];
export type DraftIn = Schemas['DraftBody'];

export const detailPlanQuery = (epic: string) => ({
  queryKey: ['planning', 'detail', epic] as const,
  queryFn: () => unwrap(http.GET('/api/v1/planning/{epic}/detail', { params: { path: { epic } } })),
});

/** Saves the draft; the plan that follows comes back. */
export function useSaveDraft(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: DraftIn) => unwrap(http.PUT('/api/v1/planning/{epic}/detail', { params: { path: { epic } }, body })),
    onSuccess: (v) => qc.setQueryData(detailPlanQuery(epic).queryKey, v),
  });
}

export const usePushPreview = (epic: string) => useMutation({
  mutationFn: () => unwrap(http.POST('/api/v1/planning/{epic}/detail/push/preview', { params: { path: { epic } } })),
});

export function usePushPlan(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationKey: ['write', 'detail-plan'],
    mutationFn: () => unwrap(http.POST('/api/v1/planning/{epic}/detail/push', { params: { path: { epic } } })),
    onSettled: () => qc.invalidateQueries(),
  });
}
