import { useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';
import type { Schemas } from '../../shared/api/types';

export const ticketQuery = (key: string) => ({
  queryKey: ['ticket', key] as const,
  queryFn: () => unwrap(http.GET('/api/v1/tickets/{key}', { params: { path: { key } } })),
});

export function usePatchTicket(key: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationKey: ['write', 'ticket', key],
    mutationFn: (body: Schemas['TicketPatchIn']) => unwrap(http.PATCH('/api/v1/tickets/{key}', { params: { path: { key } }, body })),
    onSuccess: () => qc.invalidateQueries(),
  });
}
