import { keepPreviousData, useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';
import type { Schemas } from '../../shared/api/types';

/**
 * @param tasks the Tasks whose functions are shown; by default every Task of the phase, else the shown ones, else of the first
 *              phase with functions
 * @param phase used when no Tasks are given
 * @param shown the Tasks this browser's layout shows (Setting phase); ones no longer in the anken are skipped
 */
export const matrixQuery = (epic: string, tasks?: string[], phase?: string, shown?: string[]) => ({
  queryKey: ['matrix', epic, tasks ?? null, phase ?? null, shown ?? null] as const,
  queryFn: () => unwrap(http.GET('/api/v1/anken/{epic}/matrix', {
    params: { path: { epic }, query: { ...(tasks?.length ? { tasks } : {}), ...(phase ? { phase } : {}), ...(shown?.length ? { shown } : {}) } },
  })),
  placeholderData: keepPreviousData,
});

export const functionPreviewQuery = (epic: string, draft: Schemas['FunctionDraftIn'], enabled: boolean) => ({
  queryKey: ['function-preview', epic, draft] as const,
  queryFn: () => unwrap(http.POST('/api/v1/anken/{epic}/functions/preview', { params: { path: { epic } }, body: draft })),
  enabled,
  placeholderData: keepPreviousData,
  retry: false,
});

export function useCreateFunction(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationKey: ['write', 'function', epic],
    mutationFn: (body: Schemas['FunctionCreateIn']) => unwrap(http.POST('/api/v1/anken/{epic}/functions', { params: { path: { epic } }, body })),
    onSuccess: () => qc.invalidateQueries(),
  });
}
