import { keepPreviousData, useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';
import type { Schemas } from '../../shared/api/types';

export type OnboardingView = Schemas['OnboardingView'];
export type MemberHours = Schemas['MemberHours'];
export type NewTasksIn = Schemas['NewTasksIn'];

export const onboardingListQuery = {
  queryKey: ['onboarding'] as const,
  queryFn: () => unwrap(http.GET('/api/v1/onboarding')),
};

export const onboardingQuery = (epic: string) => ({
  queryKey: ['onboarding', epic] as const,
  queryFn: () => unwrap(http.GET('/api/v1/onboarding/{epic}', { params: { path: { epic } } })),
});

/** The epic as Jira has it; nothing is saved. */
export const useLookupEpic = () => useMutation({
  mutationFn: (key: string) => unwrap(http.GET('/api/v1/onboarding/lookup', { params: { query: { key } } })),
});

export function useStartOnboarding() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (key: string) => unwrap(http.POST('/api/v1/onboarding', { body: { key } })),
    // tracking an epic runs a full sync: every screen reads again
    onSuccess: () => qc.invalidateQueries(),
  });
}

/** Records the furthest step reached (and the anken prefix when given). */
export function useSaveProgress(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (b: { step: number; anken_prefix?: string | null }) =>
      unwrap(http.PUT('/api/v1/onboarding/{epic}', { params: { path: { epic } }, body: b })),
    onSuccess: (v) => {
      qc.setQueryData(onboardingQuery(epic).queryKey, v);
      void qc.invalidateQueries({ queryKey: onboardingListQuery.queryKey, exact: true });
    },
  });
}

export function useDiscardOnboarding() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (epic: string) => unwrap(http.DELETE('/api/v1/onboarding/{epic}', { params: { path: { epic } } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['onboarding'] }),
  });
}

export function useFinishOnboarding(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap(http.POST('/api/v1/onboarding/{epic}/finish', { params: { path: { epic } } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['onboarding'] }),
  });
}

/** The template Tasks, with the names proposed for this prefix. */
export const templateTasksQuery = (epic: string, prefix: string) => ({
  queryKey: ['onboarding', epic, 'tasks', prefix] as const,
  queryFn: () => unwrap(http.GET('/api/v1/onboarding/{epic}/tasks', { params: { path: { epic }, query: { anken_prefix: prefix } } })),
  placeholderData: keepPreviousData,
});

export const usePreviewTasks = (epic: string) => useMutation({
  mutationFn: (b: NewTasksIn) => unwrap(http.POST('/api/v1/onboarding/{epic}/tasks/preview', { params: { path: { epic } }, body: b })),
});

export function useCreateTasks(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationKey: ['write', 'onboarding-tasks'],
    mutationFn: (b: NewTasksIn) => unwrap(http.POST('/api/v1/onboarding/{epic}/tasks', { params: { path: { epic } }, body: b })),
    onSettled: () => qc.invalidateQueries(),
  });
}

export const onboardingMembersQuery = (epic: string) => ({
  queryKey: ['onboarding', epic, 'members'] as const,
  queryFn: () => unwrap(http.GET('/api/v1/onboarding/{epic}/members', { params: { path: { epic } } })),
});

export function useSaveMembers(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (members: Schemas['MemberBody'][]) => unwrap(http.PUT('/api/v1/onboarding/{epic}/members', { params: { path: { epic } }, body: { members } })),
    onSuccess: (v) => {
      qc.setQueryData(onboardingMembersQuery(epic).queryKey, v);
      // members and allocations feed the master schedule and the plan
      void qc.invalidateQueries({ queryKey: ['planning'] });
      void qc.invalidateQueries({ queryKey: ['members'] });
    },
  });
}
