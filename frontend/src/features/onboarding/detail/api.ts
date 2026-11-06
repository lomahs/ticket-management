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

/** Fills the empty slots with who finishes each row soonest; the draft is saved and the plan that follows comes back. */
export function useAutoAllocate(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap(http.POST('/api/v1/planning/{epic}/detail/auto-allocate', { params: { path: { epic } } })),
    onSuccess: (v) => qc.setQueryData(detailPlanQuery(epic).queryKey, v),
  });
}

/** @param keepInput empty only Auto allocate's picks; else every pick and every date, effort and removal set by hand */
export function useClearPlan(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (keepInput: boolean) => unwrap(http.POST('/api/v1/planning/{epic}/detail/clear', { params: { path: { epic }, query: { keep_input: keepInput } } })),
    onSuccess: (v) => qc.setQueryData(detailPlanQuery(epic).queryKey, v),
  });
}

/** @param allow the PIM confirmed the days planned above a member's allocation */
export const usePushPreview = (epic: string) => useMutation({
  mutationFn: (allow: boolean) => unwrap(http.POST('/api/v1/planning/{epic}/detail/push/preview',
    { params: { path: { epic }, query: { allow_over_day: allow } } })),
});

export function usePushPlan(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationKey: ['write', 'detail-plan'],
    mutationFn: (allow: boolean) => unwrap(http.POST('/api/v1/planning/{epic}/detail/push', { params: { path: { epic }, query: { allow_over_day: allow } } })),
    onSettled: () => qc.invalidateQueries(),
  });
}

export type StepBody = Schemas['StepBody'];

/** Changes the effort, the dates or the removal of one sub-task; the plan that follows comes back. */
export function useEditStep(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: StepBody) => unwrap(http.PUT('/api/v1/planning/{epic}/detail/step', { params: { path: { epic } }, body })),
    onSuccess: (v) => qc.setQueryData(detailPlanQuery(epic).queryKey, v),
  });
}

/** Adds a function or a ticket to the estimation; the plan that follows comes back. */
export function useAddRow(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Schemas['NewRowBody']) => unwrap(http.POST('/api/v1/planning/{epic}/detail/rows', { params: { path: { epic } }, body })),
    onSuccess: (v) => {
      qc.setQueryData(detailPlanQuery(epic).queryKey, v);
      void qc.invalidateQueries({ queryKey: ['planning'] });
    },
  });
}
