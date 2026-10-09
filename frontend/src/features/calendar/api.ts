import { useMutation, useQueryClient } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';
import type { Schemas } from '../../shared/api/types';

export const calendarQuery = (month: string) => ({
  queryKey: ['calendar', month] as const,
  queryFn: () => unwrap(http.GET('/api/v1/calendar', { params: { query: { month } } })),
});

/** Leave affects planning, capacity and timesheets, so every write refreshes all screens. */
function useCalendarWrite<T, R>(fn: (v: T) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries() });
}

export const useSaveLeave = () => useCalendarWrite((v: { id?: number; body: Schemas['LeaveIn'] }) =>
  v.id ? unwrap(http.PUT('/api/v1/leaves/{leave_id}', { params: { path: { leave_id: v.id } }, body: v.body }))
    : unwrap(http.POST('/api/v1/leaves', { body: v.body })));

export const useDeleteLeave = () => useCalendarWrite((id: number) =>
  unwrap(http.DELETE('/api/v1/leaves/{leave_id}', { params: { path: { leave_id: id } } })));

export const useImportPreview = () => useMutation({
  mutationFn: (file: File | 'sample') => {
    if (file === 'sample') return unwrap(http.POST('/api/v1/leaves/import/preview', { params: { query: { sample: true } } }));
    const fd = new FormData();
    fd.append('file', file);
    return unwrap(http.POST('/api/v1/leaves/import/preview', { body: fd as never, bodySerializer: (b) => b as FormData }));
  },
});

export const useImportConfirm = () => useCalendarWrite((rows: string[][]) => unwrap(http.POST('/api/v1/leaves/import', { body: { rows } })));

export const exportUrl = (month: string) => `/api/v1/leaves/export?month=${month}`;
export const TEMPLATE_URL = '/api/v1/leaves/template';
