import { keepPreviousData } from '@tanstack/react-query';
import { http, unwrap } from '../../shared/api/client';
import { ankenParam } from '../../shared/state/anken';

export const timesheetQuery = (from: string, to: string, anken: string | null) => ({
  queryKey: ['timesheet', from, to, anken] as const,
  queryFn: () => unwrap(http.GET('/api/v1/effort/timesheet', { params: { query: { from, to, ...ankenParam(anken) } } })),
  placeholderData: keepPreviousData,
});

export const dayDetailQuery = (member: string, date: string, anken: string | null) => ({
  queryKey: ['timesheet-day', member, date, anken] as const,
  queryFn: () => unwrap(http.GET('/api/v1/effort/timesheet/{member}/{date}', { params: { path: { member, date }, query: ankenParam(anken) } })),
});

/** @param anken one anken: the analysis is per anken, so "All my anken" asks the user to pick one instead */
export const analysisQuery = (anken: string) => ({
  queryKey: ['effort-analysis', anken] as const,
  queryFn: () => unwrap(http.GET('/api/v1/effort/analysis', { params: { query: { anken } } })),
});

/** @param anken one anken, as for the analysis */
export const burndownQuery = (q: { phase?: string; task?: string; member?: string }, anken: string) => ({
  queryKey: ['effort-burndown', q.phase ?? null, q.task ?? null, q.member ?? null, anken] as const,
  queryFn: () => unwrap(http.GET('/api/v1/effort/burndown', { params: { query: { ...q, anken } } })),
  placeholderData: keepPreviousData,
});
