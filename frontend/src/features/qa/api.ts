import { useMutation } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';

export function useAsk() {
  return useMutation({ mutationFn: (q: string) => unwrap(http.POST('/api/v1/qa', { body: { q } })) });
}
