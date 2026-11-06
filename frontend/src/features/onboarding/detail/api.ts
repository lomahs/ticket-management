import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../../shared/api/client';
import type { Schemas } from '../../../shared/api/types';

export type DetailPlanView = Schemas['DetailPlanView'];
export type DraftIn = Schemas['DraftBody'];

export const detailPlanQuery = (epic: string) => ({
  queryKey: ['planning', 'detail', epic] as const,
  queryFn: () => unwrap(http.GET('/api/v1/planning/{epic}/detail', { params: { path: { epic } } })),
});

// outside ['planning']: the invalidations after a write leave the last Check alone
const checkKey = (epic: string) => ['detail-check', epic] as const;

/** The last Check of the plan, kept until the next Auto allocate or a reload; null before any. */
export function useCheckResult(epic: string): DetailPlanView | null {
  return useQuery({ queryKey: checkKey(epic), queryFn: () => null as DetailPlanView | null, enabled: false, staleTime: Infinity, gcTime: Infinity })
    .data ?? null;
}

/** Checks the plan as it stands (effort per day, overruns, phase checks) without planning again; nothing is saved. */
export function useCheckPlan(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap(http.POST('/api/v1/planning/{epic}/detail/check', { params: { path: { epic } } })),
    onSuccess: (v) => qc.setQueryData(checkKey(epic), v),
  });
}

/** The plan with what the last Check found, when there is one: its effort per day, overruns and phase checks. */
export const withCheck = (v: DetailPlanView, check: DetailPlanView | null): DetailPlanView => (check
  ? { ...v, checks: check.checks, days: check.days, over_day: check.over_day, over_budget: check.over_budget, allocated: check.allocated,
    affected: check.affected, left_out: check.left_out }
  : v);

/** Saves the draft; the plan that follows comes back. */
export function useSaveDraft(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: DraftIn) => unwrap(http.PUT('/api/v1/planning/{epic}/detail', { params: { path: { epic } }, body })),
    onSuccess: (v) => qc.setQueryData(detailPlanQuery(epic).queryKey, v),
  });
}

/**
 * Fills the empty slots with who finishes each row soonest, then plans every row again and saves the plan, which comes back;
 * the last Check goes. When it cannot plan, the people it filled are saved and the error comes back.
 */
export function useAutoAllocate(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap(http.POST('/api/v1/planning/{epic}/detail/auto-allocate', { params: { path: { epic } } })),
    onSuccess: (v) => {
      qc.setQueryData(detailPlanQuery(epic).queryKey, v);
      qc.setQueryData(checkKey(epic), null);
    },
    onError: () => qc.invalidateQueries({ queryKey: detailPlanQuery(epic).queryKey }),
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

export type ImportLine = Schemas['ImportLine'];

/** The plan's sub-tasks not in Jira yet as an .xlsx to change offline and import back. */
export const detailTemplateUrl = (epic: string) => `/api/v1/planning/${encodeURIComponent(epic)}/detail/template`;

/** Reads a filled template and checks each line against the plan; nothing is saved. */
export const useImportPreview = (epic: string) => useMutation({
  mutationFn: (file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return unwrap(http.POST('/api/v1/planning/{epic}/detail/import/preview', { params: { path: { epic } }, body: fd as never, bodySerializer: (b) => b as FormData }));
  },
});

/** Applies the lines of a preview that have no error; the plan that follows comes back. */
export function useApplyImport(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (lines: ImportLine[]) => unwrap(http.POST('/api/v1/planning/{epic}/detail/import', { params: { path: { epic } }, body: {
      lines: lines.map((l) => ({ ref: l.ref, ba_id: l.ba_id ?? null, bal_id: l.bal_id ?? null, jm_id: l.jm_id ?? null, effort_h: l.effort_h ?? null,
        start: l.start ?? null, end: l.end ?? null })),
    } })),
    // a Create or ticket effort is the estimation's: the screens built on it read it again
    onSuccess: (v) => {
      qc.setQueryData(detailPlanQuery(epic).queryKey, v);
      void qc.invalidateQueries({ queryKey: ['planning'], predicate: (q) => q.queryKey[1] !== 'detail' });
    },
  });
}
