import type { Schemas } from '../../../shared/api/types';

/** The fields of an existing ticket the screen edits, as the API names them. */
export type Field = 'status' | 'assignee_id' | 'reporter_id' | 'planned_start' | 'planned_end' | 'estimate_h';

/** Edits by ticket key; a field is only there while its value differs from Jira's. */
export type Edits = Record<string, Partial<Record<Field, string>>>;

/** A Task (under the epic) or a sub-task to create; values as the inputs hold them. */
export interface NewRow {
  ref: string;
  subtask: boolean;
  parent_key?: string;
  parent_ref?: string;
  summary: string;
  assignee_id: string;
  reporter_id: string;
  planned_start: string;
  planned_end: string;
  estimate_h: string;
}

/** A row's value of a field as the board has it. */
export function saved(r: Schemas['BoardRow'], f: Field): string {
  if (f === 'estimate_h') return String(r.estimate_h);
  return r[f] ?? '';
}

/** The edits with one field set; setting it back to the saved value drops it. */
export function withEdit(edits: Edits, r: Schemas['BoardRow'], f: Field, v: string): Edits {
  const row = { ...edits[r.key] };
  if (v === saved(r, f) || (v === '' && f !== 'status')) delete row[f];
  else row[f] = v;
  const next = { ...edits };
  if (Object.keys(row).length) next[r.key] = row;
  else delete next[r.key];
  return next;
}

export const updatesOf = (edits: Edits): Schemas['UpdateIn'][] => Object.entries(edits).map(([key, e]) => ({
  key, status: e.status as Schemas['UpdateIn']['status'], assignee_id: e.assignee_id, reporter_id: e.reporter_id,
  planned_start: e.planned_start, planned_end: e.planned_end, estimate_h: e.estimate_h != null ? Number(e.estimate_h) : undefined,
}));

/** New rows as the API takes them: new Tasks first, so their sub-tasks can name them. */
export const createsOf = (rows: NewRow[]): Schemas['NewTicketIn'][] => [...rows].sort((a, b) => Number(a.subtask) - Number(b.subtask)).map((r) => ({
  ref: r.ref, subtask: r.subtask, parent_key: r.parent_key, parent_ref: r.parent_ref, summary: r.summary.trim(), assignee_id: r.assignee_id,
  reporter_id: r.reporter_id || undefined, planned_start: r.planned_start || undefined, planned_end: r.planned_end || undefined,
  estimate_h: r.estimate_h !== '' ? Number(r.estimate_h) : undefined,
}));

let n = 0;
export const newRef = () => `n${Date.now().toString(36)}${(n++).toString(36)}`;

/** What a new row still needs before it can be reviewed; empty when it is ready. */
export function missing(r: NewRow): string[] {
  const out: string[] = [];
  if (!r.summary.trim() || r.summary.includes('*')) out.push('summary');
  if (!r.assignee_id) out.push('assignee');
  if (r.planned_start && r.planned_end && r.planned_end < r.planned_start) out.push('end before start');
  return out;
}
