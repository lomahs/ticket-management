import { keepPreviousData } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';

export const timesheetQuery = (from: string, to: string) => ({
  queryKey: ['timesheet', from, to] as const,
  queryFn: () => unwrap(http.GET('/api/v1/effort/timesheet', { params: { query: { from, to } } })),
  placeholderData: keepPreviousData,
});

export const dayDetailQuery = (member: string, date: string) => ({
  queryKey: ['timesheet-day', member, date] as const,
  queryFn: () => unwrap(http.GET('/api/v1/effort/timesheet/{member}/{date}', { params: { path: { member, date } } })),
});

export const analysisQuery = (anken?: string) => ({
  queryKey: ['effort-analysis', anken ?? null] as const,
  queryFn: () => unwrap(http.GET('/api/v1/effort/analysis', { params: { query: anken ? { anken } : {} } })),
});
