import { http, unwrap } from '../../shared/api/client';
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
