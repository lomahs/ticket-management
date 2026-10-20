import { useQuery } from '@tanstack/react-query';
import { http, unwrap } from './client';
import { ApiError } from './errors';
import type { Feature, Me, Member } from './types';

export const metaQuery = {
  queryKey: ['meta'] as const,
  queryFn: () => unwrap(http.GET('/api/v1/meta')),
  staleTime: 30_000,
};

export const membersQuery = {
  queryKey: ['members'] as const,
  queryFn: () => unwrap(http.GET('/api/v1/members')),
  staleTime: 60_000,
};

/** The signed-in user; a 401 means no session (the route gate sends it to /login). */
export const meQuery = {
  queryKey: ['me'] as const,
  queryFn: () => unwrap(http.GET('/api/v1/me')),
  staleTime: 60_000,
  retry: (n: number, e: unknown) => !(e instanceof ApiError && e.status === 401) && n < 1,
};

export function useMe() {
  return useQuery(meQuery);
}

/** Whether `me` may use `feature` at `level` (an admin may do everything). */
export function can(me: Me | undefined, feature: Feature, level: 'read' | 'write' = 'read'): boolean {
  if (!me) return false;
  if (me.admin) return true;
  const have = me.permissions[feature];
  return level === 'read' ? have === 'read' || have === 'write' : have === 'write';
}

/** Whether the signed-in user may use `feature` at `level` (an admin may do everything). */
export function useCan(feature: Feature, level: 'read' | 'write' = 'read'): boolean {
  return can(useMe().data, feature, level);
}

export function useMeta() {
  return useQuery(metaQuery);
}

export function useMembers() {
  return useQuery(membersQuery);
}

/** Members by id; empty until loaded. */
export function useMemberMap(): Record<string, Member> {
  const { data } = useMembers();
  return Object.fromEntries((data ?? []).map((m) => [m.id, m]));
}

/** Whether the backend can write to Jira (phase 1 Jira mode cannot). */
export function useCapability(name: 'jiraWrite'): boolean {
  const { data } = useMeta();
  return name === 'jiraWrite' ? !!data?.capabilities.jira_write : false;
}

export const JIRA_WRITE_OFF = 'Updating Jira arrives in phase 2';
export const VIEW_ONLY = 'You have view-only access';

/** A control that writes to Jira is on only when Jira can be written AND the user has write on `feature`; `off` says why it is not (permission first). */
export function useJiraWrite(feature: Feature): { ok: boolean; off: string | undefined } {
  const jira = useCapability('jiraWrite');
  const perm = useCan(feature, 'write');
  return { ok: jira && perm, off: !perm ? VIEW_ONLY : !jira ? JIRA_WRITE_OFF : undefined };
}
