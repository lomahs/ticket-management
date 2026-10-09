import { useQuery } from '@tanstack/react-query';
import { http, unwrap } from './client';
import type { Member } from './types';

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
