import { http, unwrap } from '../../shared/api/client';

export const overviewQuery = {
  queryKey: ['anken'] as const,
  queryFn: () => unwrap(http.GET('/api/v1/anken')),
};

export const ankenDetailQuery = (epic: string) => ({
  queryKey: ['anken', epic] as const,
  queryFn: () => unwrap(http.GET('/api/v1/anken/{epic}', { params: { path: { epic } } })),
});
