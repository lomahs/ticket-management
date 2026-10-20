import { useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';
import type { Schemas } from '../../shared/api/types';

export const usersQuery = {
  queryKey: ['admin', 'users'] as const,
  queryFn: () => unwrap(http.GET('/api/v1/admin/users')),
};

/** Pending access requests (an empty `status=` is a 400, so the value is always sent). */
export const pendingRequestsQuery = {
  queryKey: ['admin', 'access-requests', 'pending'] as const,
  queryFn: () => unwrap(http.GET('/api/v1/admin/access-requests', { params: { query: { status: 'pending' } } })),
};

/** A change to a user also changes what /me says for that user and what the sidebar shows. */
function useAdminWrite<T, R>(fn: (v: T) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: async () => {
      await Promise.all([qc.invalidateQueries({ queryKey: ['admin'] }), qc.invalidateQueries({ queryKey: ['me'] })]);
    },
  });
}

export const usePatchUser = () => useAdminWrite(({ id, body }: { id: string; body: Schemas['UserPatchIn'] }) => unwrap(
  http.PATCH('/api/v1/admin/users/{memberId}', { params: { path: { memberId: id } }, body })));
export const useApprove = () => useAdminWrite(({ id, body }: { id: number; body: Schemas['ApproveIn'] }) => unwrap(
  http.POST('/api/v1/admin/access-requests/{id}/approve', { params: { path: { id } }, body })));
export const useReject = () => useAdminWrite((id: number) => unwrap(
  http.POST('/api/v1/admin/access-requests/{id}/reject', { params: { path: { id } } })));
