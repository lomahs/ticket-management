import { keepPreviousData } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';
import { ankenParam } from '../../shared/state/anken';

/** @param anken null: every anken the viewer sees */
export const ticketCheckQuery = (anken: string | null) => ({
  queryKey: ['ticket-check', anken] as const,
  queryFn: () => unwrap(http.GET('/api/v1/ticket-check', { params: { query: ankenParam(anken) } })),
  placeholderData: keepPreviousData,
});
