import { useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';
import type { Schemas } from '../../shared/api/types';
import { ankenParam } from '../../shared/state/anken';

/** The cards of the switcher's anken (null: every anken the viewer sees). */
export const overviewQuery = (anken: string | null) => ({
  queryKey: ['anken', 'list', anken] as const,
  queryFn: () => unwrap(http.GET('/api/v1/anken', { params: { query: ankenParam(anken) } })),
});

export const ankenDetailQuery = (epic: string) => ({
  queryKey: ['anken', epic] as const,
  queryFn: () => unwrap(http.GET('/api/v1/anken/{epic}', { params: { path: { epic } } })),
});

export const ankenMembersQuery = (epic: string) => ({
  queryKey: ['anken', epic, 'members'] as const,
  queryFn: () => unwrap(http.GET('/api/v1/anken/{epic}/members', { params: { path: { epic } } })),
});

/** Members decide who the pickers offer, the cards' avatars and /me's anken, so everything that lists them is refetched. */
function useMembersWrite<T, R>(fn: (v: T) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: async () => {
      await Promise.all(['anken', 'members', 'me', 'admin'].map((k) => qc.invalidateQueries({ queryKey: [k] })));
    },
  });
}

export const useAddAnkenMember = (epic: string) => useMembersWrite((memberId: string) => unwrap(
  http.POST('/api/v1/anken/{epic}/members', { params: { path: { epic } }, body: { member_id: memberId } })));
export const useRemoveAnkenMember = (epic: string) => useMembersWrite((memberId: string) => unwrap(
  http.DELETE('/api/v1/anken/{epic}/members/{memberId}', { params: { path: { epic, memberId } } })));
export const usePatchPerson = () => useMembersWrite(({ id, body }: { id: string; body: Schemas['PersonPatchIn'] }) => unwrap(
  http.PATCH('/api/v1/people/{memberId}', { params: { path: { memberId: id } }, body })));
export const useCreatePerson = () => useMembersWrite((body: Schemas['NewPersonIn']) => unwrap(http.POST('/api/v1/people', { body })));
