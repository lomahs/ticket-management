import { http, unwrap } from '../../shared/api/client';
import { ankenParam } from '../../shared/state/anken';

export type Backlog = 'week' | 'month';

export const myWorkQuery = (member: string, backlog: Backlog, anken: string | null) => ({
  queryKey: ['my-work', member, backlog, anken] as const,
  queryFn: () => unwrap(http.GET('/api/v1/my-work', { params: { query: { member, backlog, ...ankenParam(anken) } } })),
});
