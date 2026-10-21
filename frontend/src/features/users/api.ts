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
function useAdminWrite<T, R>(fn: (v: T) => Promise<R>, also: readonly string[] = []) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: async () => {
      await Promise.all(['admin', 'me', ...also].map((k) => qc.invalidateQueries({ queryKey: [k] })));
    },
  });
}

/** A merge moves anken membership, leave, worklogs and assignments, so every screen that lists people reloads. */
const MERGED = ['members', 'anken', 'planning', 'calendar', 'my-work', 'timesheet'] as const;

/** A patch can replace the user's anken, and allocations count only on the anken a member belongs to. */
const ANKEN_CHANGED = ['allocations', 'timesheet', 'timesheet-day', 'planning'] as const;

export const usePatchUser = () => useAdminWrite(({ id, body }: { id: string; body: Schemas['UserPatchIn'] }) => unwrap(
  http.PATCH('/api/v1/admin/users/{memberId}', { params: { path: { memberId: id } }, body })), ANKEN_CHANGED);
export const useApprove = () => useAdminWrite(({ id, body }: { id: number; body: Schemas['ApproveIn'] }) => unwrap(
  http.POST('/api/v1/admin/access-requests/{id}/approve', { params: { path: { id } }, body })));
export const useReject = () => useAdminWrite((id: number) => unwrap(
  http.POST('/api/v1/admin/access-requests/{id}/reject', { params: { path: { id } } })));
export const useMerge = () => useAdminWrite(
  (body: Schemas['MergeIn']) => unwrap(http.POST('/api/v1/admin/members/merge', { body })), MERGED);
