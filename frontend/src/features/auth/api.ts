import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { http, unwrap } from '../../shared/api/client';
import { forgetAnken } from '../../shared/state/anken';

export const authInfoQuery = {
  queryKey: ['auth-info'] as const,
  queryFn: () => unwrap(http.GET('/api/v1/auth/info')),
  staleTime: 60_000,
};

export function useLogin() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { username: string; password: string }) => unwrap(http.POST('/api/v1/auth/login', { body })),
    onSuccess: (me) => {
      qc.setQueryData(['me'], me);
      return qc.invalidateQueries();
    },
  });
}

/** Signing out also forgets the switcher's anken: the next user of this browser may not see it. */
export function useLogout() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  return useMutation({
    mutationFn: () => unwrap(http.POST('/api/v1/auth/logout')),
    onSuccess: async () => {
      forgetAnken();
      await navigate({ to: '/login', search: {} });
      qc.clear();
    },
  });
}

export function useRequestAccess() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (note: string | null) => unwrap(http.POST('/api/v1/me/access-request', { body: { note } })),
    onSuccess: (me) => { qc.setQueryData(['me'], me); },
  });
}
