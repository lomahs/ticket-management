import { useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../../shared/api/client';
import type { Schemas } from '../../../shared/api/types';

export type DetailPlanView = Schemas['DetailPlanView'];
export type DraftIn = Schemas['DraftBody'];

export const detailPlanQuery = (epic: string) => ({
  queryKey: ['planning', 'detail', epic] as const,
  queryFn: () => unwrap(http.GET('/api/v1/planning/{epic}/detail', { params: { path: { epic } } })),
});

// one at a time, in the order they were made: each answer is the whole plan, so the last one sent is the one shown
const planScope = (epic: string) => ({ id: `detail-plan:${epic}` });

/** Saves the draft; the plan that follows comes back. */
export function useSaveDraft(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    scope: planScope(epic),
    mutationFn: (body: DraftIn) => unwrap(http.PUT('/api/v1/planning/{epic}/detail', { params: { path: { epic } }, body })),
    onSuccess: (v) => qc.setQueryData(detailPlanQuery(epic).queryKey, v),
  });
}

/**
 * Fills the empty slots with who finishes each row soonest, then fills what is not planned yet of each line around the cells
 * typed by hand; the plan that follows comes back. When it cannot plan, the people it filled are saved and the error comes back.
 */
export function useAutoAllocate(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap(http.POST('/api/v1/planning/{epic}/detail/auto-allocate', { params: { path: { epic } } })),
    onSuccess: (v) => qc.setQueryData(detailPlanQuery(epic).queryKey, v),
    onError: () => qc.invalidateQueries({ queryKey: detailPlanQuery(epic).queryKey }),
  });
}

/**
 * @param keepInput empty only what Auto allocate filled (cells and picks); else every cell, pick, effort and removal of the rows not in
 *                  Jira yet, and every Jira change not pushed
 */
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
export type MatrixLine = Schemas['MatrixLine'];
export type CellIn = Schemas['CellBody'];

/** Saves hours on days of lines (0 clears the day); the plan that follows comes back. */
export function useSaveCells(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    scope: planScope(epic),
    mutationFn: (cells: CellIn[]) => unwrap(http.PUT('/api/v1/planning/{epic}/detail/cells', { params: { path: { epic } }, body: { cells } })),
    onSuccess: (v) => qc.setQueryData(detailPlanQuery(epic).queryKey, v),
  });
}

/**
 * Changes the Total (its Remaining, Spent staying) or the PIC of a sub-task in Jira, kept here until the push; a null field keeps
 * what it is. The plan that follows comes back.
 */
export function useEditJiraLine(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    scope: planScope(epic),
    mutationFn: ({ key, ...body }: { key: string } & Schemas['JiraLineBody']) =>
      unwrap(http.PUT('/api/v1/planning/{epic}/detail/jira/{key}', { params: { path: { epic, key } }, body })),
    onSuccess: (v) => qc.setQueryData(detailPlanQuery(epic).queryKey, v),
  });
}

/** Stores a snapshot of the whole plan; the plan comes back with the time of that version. */
export function useSaveVersion(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap(http.POST('/api/v1/planning/{epic}/detail/versions', { params: { path: { epic } } })),
    onSuccess: (v) => qc.setQueryData(detailPlanQuery(epic).queryKey, v),
  });
}

/** Changes the effort or the removal of one step of a row not in Jira yet; the plan that follows comes back. */
export function useEditStep(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    scope: planScope(epic),
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

/** The plan's sub-tasks not in Jira yet, with a column per day, as an .xlsx to change offline and import back. */
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
        cells: l.cells })),
    } })),
    // a Create or ticket effort is the estimation's: the screens built on it read it again
    onSuccess: (v) => {
      qc.setQueryData(detailPlanQuery(epic).queryKey, v);
      void qc.invalidateQueries({ queryKey: ['planning'], predicate: (q) => q.queryKey[1] !== 'detail' });
    },
  });
}
