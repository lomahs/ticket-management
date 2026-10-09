import type { Config, ImportRow, LeaveTypeKey, Settings, Snapshot, Status, Task } from './types';

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function req<T>(method: string, path: string, body?: unknown): Promise<T> {
  const init: RequestInit = { method, headers: {} };
  if (body instanceof FormData) init.body = body;
  else if (body !== undefined) {
    init.body = JSON.stringify(body);
    init.headers = { 'Content-Type': 'application/json' };
  }
  const res = await fetch('/api' + path, init);
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    const d = data?.detail;
    const msg = typeof d === 'string' ? d : Array.isArray(d) ? d.map((x) => x.msg).join('; ') : res.statusText;
    throw new ApiError(res.status, msg);
  }
  return data as T;
}

export interface PlanChange { key: string; ps: string; pe: string; who: string }
export interface NewFunction {
  parent: string; phase: string; fid: string; name: string; ba: string;
  rows: { ps: string; pe: string; est: number; who: string }[];
}

export const api = {
  state: () => req<Snapshot>('GET', '/state'),
  sync: () => req<Snapshot>('POST', '/sync'),
  ticket: (key: string) => req<Task>('GET', `/tickets/${encodeURIComponent(key)}`),
  patchTicket: (key: string, patch: Partial<{ status: Status; assignee: string; ps: string; pe: string }>) =>
    req<Snapshot>('PATCH', `/tickets/${encodeURIComponent(key)}`, patch),
  pushPlan: (changes: PlanChange[]) => req<Snapshot>('POST', '/plan/push', { changes }),
  createFunction: (body: NewFunction) => req<Snapshot>('POST', '/functions', body),
  chat: (q: string) => req<{ text: string; chips: string[] }>('POST', '/chat', { q }),

  saveLeave: (body: { editId?: number; who: string; type: LeaveTypeKey; from: string; to: string; note: string }) =>
    req<Snapshot>('POST', '/leaves', body),
  deleteLeave: (id: number) => req<Snapshot>('DELETE', `/leaves/${id}`),
  importPreview: (file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return req<{ file: string; rows: ImportRow[] }>('POST', '/leaves/import/preview', fd);
  },
  importSample: () => req<{ file: string; rows: ImportRow[] }>('GET', '/leaves/import/sample'),
  importConfirm: (file: string, rows: string[][]) =>
    req<{ imported: number; month: string | null; snapshot: Snapshot }>('POST', '/leaves/import', { file, rows }),
  templateUrl: '/api/leaves/template',
  exportUrl: (month: string) => `/api/leaves/export?month=${month}`,

  addHoliday: (date: string, name: string, c: string) => req<Snapshot>('POST', '/holidays', { date, name, c }),
  deleteHoliday: (date: string) => req<Snapshot>('DELETE', `/holidays/${date}`),
  addExtraDay: (date: string, note: string) => req<Snapshot>('POST', '/extra-days', { date, note }),
  deleteExtraDay: (date: string) => req<Snapshot>('DELETE', `/extra-days/${date}`),
  patchConfig: (patch: Partial<Config>) => req<Snapshot>('PATCH', '/config', patch),
  patchMember: (id: string, patch: { role?: string; perm?: string }) => req<Snapshot>('PATCH', `/members/${id}`, patch),

  saveJira: (body: { url: string; email: string; token?: string; interval: string }) => req<Snapshot>('PUT', '/settings/jira', body),
  testJira: (url: string, token?: string) => req<{ status: 'ok' | 'fail' }>('POST', '/settings/jira/test', { url, token }),
  addEpic: (key: string) => req<Snapshot>('POST', '/settings/epics', { key }),
  deleteEpic: (key: string) => req<Snapshot>('DELETE', `/settings/epics/${encodeURIComponent(key)}`),
  patchSettings: (patch: Partial<Pick<Settings, 'map' | 'rule' | 'alias'>>) => req<Snapshot>('PATCH', '/settings', patch),
};
