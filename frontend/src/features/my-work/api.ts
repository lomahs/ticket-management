import { http, unwrap } from '../../shared/api/client';

export type Backlog = 'week' | 'month';

export const myWorkQuery = (member: string, backlog: Backlog) => ({
  queryKey: ['my-work', member, backlog] as const,
  queryFn: () => unwrap(http.GET('/api/v1/my-work', { params: { query: { member, backlog } } })),
});
