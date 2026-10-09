import { useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';

export function useSyncNow() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (full: boolean) => unwrap(http.POST('/api/v1/sync', { body: { full } })),
    onSettled: () => qc.invalidateQueries(),
  });
}
