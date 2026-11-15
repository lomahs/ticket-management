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

/** An anken whose epic does not exist yet: planned under a draft key, seen in Onboarding only. */
export function useStartDraft() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => unwrap(http.POST('/api/v1/onboarding/draft', { body: { name } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['onboarding'] }),
  });
}

/** Links a draft anken to its epic; everything of the draft moves under the epic's key. */
export function useLinkEpic(draft: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (key: string) => unwrap(http.POST('/api/v1/onboarding/{epic}/link', { params: { path: { epic: draft } }, body: { key } })),
    // tracking an epic runs a full sync: every screen reads again
    onSuccess: () => qc.invalidateQueries(),
  });
}

/** Records the furthest step reached (and the anken prefix when given). */
export function useSaveProgress(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (b: { step: number; anken_prefix?: string | null; labels?: string[] | null }) =>
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

/** The Phases & tasks of an anken without its epic: its own, or the default until it saves them. */
export const draftStructureQuery = (epic: string) => ({
  queryKey: ['onboarding', epic, 'structure'] as const,
  queryFn: () => unwrap(http.GET('/api/v1/onboarding/{epic}/structure', { params: { path: { epic } } })),
});

export function useSaveDraftStructure(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Schemas['StructureIn']) => unwrap(http.PUT('/api/v1/onboarding/{epic}/structure', { params: { path: { epic } }, body })),
    onSuccess: (v) => {
      qc.setQueryData(draftStructureQuery(epic).queryKey, v);
      // the template Tasks feed Setting Phase, the estimation and the plan
      void qc.invalidateQueries({ queryKey: ['onboarding', epic, 'tasks'] });
      void qc.invalidateQueries({ queryKey: ['planning'] });
    },
  });
}

/** Reads a Phases & tasks file for the form; nothing is saved. */
export const useImportDraftStructure = (epic: string) => useMutation({
  mutationFn: (file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return unwrap(http.POST('/api/v1/onboarding/{epic}/structure/import', {
      params: { path: { epic } }, body: fd as never, bodySerializer: (b) => b as FormData,
    }));
  },
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
    mutationFn: (body: Schemas['MembersIn']) => unwrap(http.PUT('/api/v1/onboarding/{epic}/members', { params: { path: { epic } }, body })),
    onSuccess: (v) => {
      qc.setQueryData(onboardingMembersQuery(epic).queryKey, v);
      // members and allocations feed the master schedule and the plan
      void qc.invalidateQueries({ queryKey: ['planning'] });
      void qc.invalidateQueries({ queryKey: ['members'] });
    },
  });
}

/** A new person who joins this anken; the admin gets the access request when they have an e-mail (a JM needs none). */
export function useCreateMember(epic: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (b: Omit<Schemas['NewPersonIn'], 'epics'>) => unwrap(http.POST('/api/v1/people', { body: { ...b, epics: [epic] } })),
    onSuccess: async () => {
      await Promise.all([onboardingMembersQuery(epic).queryKey, ['members'], ['anken'], ['admin']].map((queryKey) => qc.invalidateQueries({ queryKey })));
    },
  });
}
