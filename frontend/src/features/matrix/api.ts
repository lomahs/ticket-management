import { keepPreviousData, useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';
import type { Schemas } from '../../shared/api/types';

export const matrixQuery = (epic: string) => ({
  queryKey: ['matrix', epic] as const,
  queryFn: () => unwrap(http.GET('/api/v1/anken/{epic}/matrix', { params: { path: { epic } } })),
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
