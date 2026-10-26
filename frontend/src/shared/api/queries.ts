import { useQuery } from '@tanstack/react-query';
import { ankenParam, useAnkenScope } from '../state/anken';
import { http, unwrap } from './client';
import { ApiError } from './errors';
import type { Feature, Me, Member } from './types';

export const metaQuery = {
  queryKey: ['meta'] as const,
  queryFn: () => unwrap(http.GET('/api/v1/meta')),
  staleTime: 30_000,
};

/** @param anken null: the members of every anken the viewer sees */
export const membersQuery = (anken: string | null) => ({
  queryKey: ['members', anken] as const,
  queryFn: () => unwrap(http.GET('/api/v1/members', { params: { query: ankenParam(anken) } })),
  staleTime: 60_000,
});

/** Every anken the viewer may see, whatever the switcher says: the switcher's options. */
export const visibleAnkenQuery = {
  queryKey: ['anken', 'visible'] as const,
  queryFn: () => unwrap(http.GET('/api/v1/anken')),
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

/** The members of the switcher's anken. */
export function useMembers() {
  return useQuery(membersQuery(useAnkenScope().anken));
}

/**
 * The anken the viewer may see, as key and title. Without read on the Overview the cards cannot be listed, so the
 * keys come from /me.
 */
export function useVisibleAnken(): { key: string; title: string }[] {
  const me = useMe().data;
  const listable = can(me, 'overview');
  const cards = useQuery({ ...visibleAnkenQuery, enabled: listable }).data;
  return listable ? cards ?? [] : (me?.anken ?? []).map((key) => ({ key, title: '' }));
}

/**
 * The members a screen may pick (view as, give leave): the people of the switcher's anken and the signed-in user. The
 * others in /members are only named because an issue refers to them; the server rejects them. Names use {@link useMemberMap}.
 */
export function usePickableMembers(): Member[] {
  const me = useMe().data;
  return (useMembers().data ?? []).filter((m) => m.people || m.id === me?.member_id);
}

/** Members by id; empty until loaded. */
export function useMemberMap(): Record<string, Member> {
  const { data } = useMembers();
  return Object.fromEntries((data ?? []).map((m) => [m.id, m]));
}

/** Whether the backend can write to Jira at all, and whether Planning may push to it (the demo site only, for now). */
export function useCapability(name: 'jiraWrite' | 'planningPush'): boolean {
  const { data } = useMeta();
  return name === 'jiraWrite' ? !!data?.capabilities.jira_write : !!data?.capabilities.planning_push;
}

export const JIRA_WRITE_OFF = 'Writing to Jira is not available';
export const PLANNING_PUSH_OFF = 'Pushing a plan to Jira is not available yet';
export const VIEW_ONLY = 'You have view-only access';
export const ADMIN_ONLY = 'Only admins update Jira';

/** A control that writes to Jira is on only when Jira can be written AND the user has write on `feature`; `off` says why it is not (permission first). */
export function useJiraWrite(feature: Feature, capability: 'jiraWrite' | 'planningPush' = 'jiraWrite'): { ok: boolean; off: string | undefined } {
  const jira = useCapability(capability);
  const perm = useCan(feature, 'write');
  return { ok: jira && perm, off: !perm ? VIEW_ONLY : !jira ? (capability === 'planningPush' ? PLANNING_PUSH_OFF : JIRA_WRITE_OFF) : undefined };
}

/** Changing tickets in Jira (ticket panel, new functions): admins only. */
export function useAdminJiraWrite(): { ok: boolean; off: string | undefined } {
  const jira = useCapability('jiraWrite');
  const admin = !!useMe().data?.admin;
  return { ok: jira && admin, off: !admin ? ADMIN_ONLY : !jira ? JIRA_WRITE_OFF : undefined };
}
