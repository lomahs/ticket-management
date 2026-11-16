import { useMutation, useMutationState, useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';
import { http, unwrap } from '../../../shared/api/client';
import type { Schemas } from '../../../shared/api/types';

export type DetailPlanView = Schemas['DetailPlanView'];
export type DraftIn = Schemas['DraftBody'];

export const detailPlanQuery = (epic: string) => ({
  queryKey: ['planning', 'detail', epic] as const,
  queryFn: () => unwrap(http.GET('/api/v1/planning/{epic}/detail', { params: { path: { epic } } })),
});

// every write of the plan, one at a time in the order they were made: each answer is the whole plan, so the last one sent is the
// one shown, and a write never goes out alongside a cell still being saved
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
    scope: planScope(epic),
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
    scope: planScope(epic),
    mutationFn: (keepInput: boolean) => unwrap(http.POST('/api/v1/planning/{epic}/detail/clear', { params: { path: { epic }, query: { keep_input: keepInput } } })),
    onSuccess: (v) => qc.setQueryData(detailPlanQuery(epic).queryKey, v),
  });
}

/** What to push: the lines picked (`refs`, all when null), and `force` past Not yet plan, steps out of order and the master schedule. */
export type PushBody = Schemas['PushBody'];
export const PUSH_ALL: PushBody = { refs: null, force: false };

export const usePushPreview = (epic: string) => useMutation({
  scope: planScope(epic),
  mutationFn: (body: PushBody) => unwrap(http.POST('/api/v1/planning/{epic}/detail/push/preview', { params: { path: { epic } }, body })),
});

export function usePushPlan(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationKey: ['write', 'detail-plan'],
    scope: planScope(epic),
    mutationFn: (body: PushBody) => unwrap(http.POST('/api/v1/planning/{epic}/detail/push', { params: { path: { epic } }, body })),
    onSettled: () => qc.invalidateQueries(),
  });
}

export type StepBody = Schemas['StepBody'];
export type MatrixLine = Schemas['MatrixLine'];
export type CellIn = Schemas['CellBody'];

const cellsKey = (epic: string) => ['detail-plan', 'cells', epic] as const;

/**
 * Saves hours on days of lines (0 clears the day); the plan that follows comes back.
 *
 * `send` puts the cell in the plan's queue of writes at once, so a write made after it still goes after it. The first goes out
 * at once; the cells typed while a write is in flight wait and go out together, in one request, when their turn comes (a cell
 * typed twice: its last hours). `typed`: the cells not saved yet, in the order they were typed, to show them at once; a cell
 * whose write is refused drops out of it.
 */
export function useCellWriter(epic: string) {
  const qc = useQueryClient();
  // the cells waiting for their turn, by ref and day: the first write whose turn comes takes them all
  const waiting = useRef(new Map<string, CellIn>());
  const save = useMutation({
    mutationKey: cellsKey(epic),
    scope: planScope(epic),
    mutationFn: async (_cell: CellIn) => {
      const cells = [...waiting.current.values()];
      waiting.current.clear();
      // taken by an earlier write: nothing left to send
      if (!cells.length) return qc.getQueryData<DetailPlanView>(detailPlanQuery(epic).queryKey) ?? null;
      return unwrap(http.PUT('/api/v1/planning/{epic}/detail/cells', { params: { path: { epic } }, body: { cells } }));
    },
    onSuccess: (v) => { if (v) qc.setQueryData(detailPlanQuery(epic).queryKey, v); },
  });
  const typed = useMutationState({ filters: { mutationKey: cellsKey(epic), status: 'pending' }, select: (m) => m.state.variables as CellIn });
  const send = (cell: CellIn) => {
    const k = `${cell.ref}\u0000${cell.day}`;
    waiting.current.delete(k); // typed again: it goes in the order of its last hours
    waiting.current.set(k, cell);
    return save.mutateAsync(cell);
  };
  return { send, typed };
}

/** Moves the hours of lines from today on by working days of each line's PIC (negative: earlier); the plan that follows comes back. */
export function useShiftPlan(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    scope: planScope(epic),
    mutationFn: (body: { refs: string[]; days: number }) => unwrap(http.POST('/api/v1/planning/{epic}/detail/shift', { params: { path: { epic } }, body })),
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

/**
 * Stores a snapshot of the whole plan; the plan comes back with the time of that version. Refused until the plan is in Jira and
 * up to date with it; the first version is the anken's Baseline plan.
 */
export function useSaveVersion(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    scope: planScope(epic),
    mutationFn: () => unwrap(http.POST('/api/v1/planning/{epic}/detail/versions', { params: { path: { epic }, query: { baseline: true } } })),
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

/** Deletes a removed step of a row not in Jira yet for good (a row left without one goes too); the plan that follows comes back. */
export function useDeleteStep(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    scope: planScope(epic),
    mutationFn: ({ rowId, step }: { rowId: number; step: string | null }) => unwrap(http.DELETE('/api/v1/planning/{epic}/detail/step', {
      params: { path: { epic }, query: { row_id: rowId, ...(step ? { step } : {}) } } })),
    onSuccess: (v) => qc.setQueryData(detailPlanQuery(epic).queryKey, v),
  });
}

/** Adds a function or a ticket to the estimation; the plan that follows comes back. */
export function useAddRow(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    scope: planScope(epic),
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
    scope: planScope(epic),
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
