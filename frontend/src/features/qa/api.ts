import { useMutation } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';
import { ankenParam, useAnkenScope } from '../../shared/state/anken';

/** Answers from the switcher's anken only. */
export function useAsk() {
  const { anken } = useAnkenScope();
  return useMutation({ mutationFn: (q: string) => unwrap(http.POST('/api/v1/qa', { params: { query: ankenParam(anken) }, body: { q } })) });
}
