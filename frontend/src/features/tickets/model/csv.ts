import type { Schemas } from '../../../shared/api/types';
import { parseCsv } from '../../../shared/format/csv';
import { parseYmd, ymd } from '../../../shared/format/dates';
import { STATUS, statusOf } from '../../../shared/format/labels';
import { newRef, saved, withEdit, type Edits, type Field, type NewRow } from './draft';
import { valueOf, type Item } from './list';

/** The app's ticket CSV: what Export writes and Import reads. People are Jira emails, dates yyyy/MM/dd, the estimate hours. */
export const TEMPLATE = ['Key', 'Issue Type', 'Parent', 'Summary', 'Status', 'Assignee', 'Reporter', 'Start', 'End', 'Estimate (h)'];

/** The rows of the export: the values the list shows, edits included; a draft has no key, a sub-task under a new Task no parent. */
export function exportRows(items: Item[], edits: Edits, epic: string, email: (id: string) => string): string[][] {
  return items.map((it) => {
    const v = (f: Field) => valueOf(it, edits, f);
    return [it.row ? it.id : '', it.task ? 'Task' : 'Sub-task', it.parent.startsWith('<new:') ? '' : it.task ? epic : it.parent,
      v('summary'), statusOf(v('status')).label, v('assignee_id') && email(v('assignee_id')), v('reporter_id') && email(v('reporter_id')),
      ymd(v('planned_start')), ymd(v('planned_end')), v('estimate_h')];
  });
}

export interface ImportContext {
  epic: string;
  /** Every Task and sub-task of the anken in Jira, by key. */
  rows: Record<string, Schemas['BoardRow']>;
  /** The keys of the anken's Tasks. */
  tasks: Set<string>;
  members: Schemas['Member'][];
}

export interface ImportError { line: number; key: string; message: string }

export type ImportResult =
  | { ok: true; edits: Edits; news: NewRow[]; updated: string[]; created: number; errors: ImportError[] }
  | { ok: false; message: string };

const norm = (s: string) => s.trim().toLowerCase();

/**
 * Reads a file of the app's template into the screen's drafts: a row with a Key updates that ticket (its values replace edits not
 * committed yet), a row without one is a new ticket. An empty cell leaves the field as it is. A row with any mistake is skipped
 * whole and reported with its line; a header that is not the template refuses the file.
 */
export function importCsv(text: string, ctx: ImportContext, edits: Edits): ImportResult {
  const { sep, records } = parseCsv(text);
  if (!records.length) return { ok: false, message: 'The file is empty' };
  const header = records[0].map(norm);
  const missing = TEMPLATE.filter((h) => !header.includes(norm(h)));
  const unknown = records[0].filter((h) => h.trim() && !TEMPLATE.some((t) => norm(t) === norm(h)));
  if (missing.length || unknown.length) {
    return { ok: false, message: ['Not the ticket template', missing.length && `missing ${missing.join(', ')}`,
      unknown.length && `unknown ${unknown.join(', ')}`].filter(Boolean).join(' · ') };
  }
  const col = (r: string[], h: string) => (r[header.indexOf(norm(h))] ?? '').trim();

  const byEmail = new Map(ctx.members.filter((m) => m.email).map((m) => [norm(m.email), m.id]));
  const exported = (id: string | null | undefined) => (id ? ctx.members.find((m) => m.id === id)?.email || ctx.members.find((m) => m.id === id)?.name || id : '');
  let out = edits;
  const news: NewRow[] = [];
  const updated = new Set<string>();
  const errors: ImportError[] = [];
  const seen = new Set<string>();

  for (const [i, r] of records.slice(1).entries()) {
    const line = i + 2;
    const key = col(r, 'Key').toUpperCase();
    const problems: string[] = [];
    // A person as the file names them: a member's Jira email, or the value the export wrote for the ticket's current one.
    const person = (h: string, now?: string | null) => {
      const v = col(r, h);
      if (!v) return '';
      if (now && v === exported(now)) return now;
      const id = byEmail.get(norm(v));
      if (!id) problems.push(`${h}: no member has the email ${v}`);
      return id ?? '';
    };
    const date = (h: string) => {
      const v = col(r, h);
      if (!v) return '';
      const iso = parseYmd(v, true);
      if (!iso) problems.push(`${h}: ${v} is not a date (yyyy/MM/dd)`);
      return iso ?? '';
    };
    const hours = () => {
      const v = col(r, 'Estimate (h)');
      if (!v) return '';
      const n = Number(sep === ';' && !v.includes('.') ? v.replace(',', '.') : v);
      if (!Number.isFinite(n) || n < 0) problems.push(`Estimate (h): ${v} is not a number of hours`);
      return Number.isFinite(n) ? String(n) : '';
    };

    if (key) {
      const row = ctx.rows[key];
      if (!row) { errors.push({ line, key, message: `${key} is not a ticket of ${ctx.epic}` }); continue; }
      if (seen.has(key)) { errors.push({ line, key, message: `${key} is in the file more than once` }); continue; }
      seen.add(key);
      const statusText = col(r, 'Status');
      const status = statusText ? (Object.keys(STATUS) as (keyof typeof STATUS)[]).find((k) => norm(STATUS[k].label) === norm(statusText) || norm(k) === norm(statusText)) : '';
      if (status == null) problems.push(`Status: ${statusText} is not one of ${Object.values(STATUS).map((s) => s.label).join(', ')}`);
      const values: Partial<Record<Field, string>> = {
        summary: col(r, 'Summary'), status: status ?? '', assignee_id: person('Assignee', row.assignee_id), reporter_id: person('Reporter', row.reporter_id),
        planned_start: date('Start'), planned_end: date('End'), estimate_h: hours(),
      };
      const start = values.planned_start || out[key]?.planned_start || saved(row, 'planned_start');
      const end = values.planned_end || out[key]?.planned_end || saved(row, 'planned_end');
      if (start && end && end < start) problems.push('End is before Start');
      if (problems.length) { errors.push({ line, key, message: problems.join(' · ') }); continue; }
      for (const [f, v] of Object.entries(values) as [Field, string][]) {
        if (v) out = withEdit(out, row, f, v);
      }
      if (out[key]) updated.add(key);
      continue;
    }

    const type = norm(col(r, 'Issue Type')).replace(/[^a-z]/g, '');
    const parent = col(r, 'Parent').toUpperCase();
    if (type !== 'task' && type !== 'subtask') problems.push('Issue Type: write Task or Sub-task for a new ticket');
    if (type === 'subtask' && !ctx.tasks.has(parent)) problems.push(`Parent: ${parent || 'empty'} is not a Task of ${ctx.epic}`);
    if (type === 'task' && parent && parent !== ctx.epic) problems.push(`Parent: a new Task goes under ${ctx.epic}`);
    const summary = col(r, 'Summary');
    if (!summary) problems.push('Summary is required for a new ticket');
    const assignee = person('Assignee');
    if (!col(r, 'Assignee')) problems.push('Assignee is required for a new ticket');
    const n: NewRow = {
      ref: newRef(), subtask: type === 'subtask', ...(type === 'subtask' ? { parent_key: parent } : {}), summary, assignee_id: assignee,
      reporter_id: person('Reporter'), planned_start: date('Start'), planned_end: date('End'), estimate_h: hours(),
    };
    if (n.planned_start && n.planned_end && n.planned_end < n.planned_start) problems.push('End is before Start');
    if (problems.length) { errors.push({ line, key: '', message: problems.join(' · ') }); continue; }
    news.push(n);
  }
  return { ok: true, edits: out, news, updated: [...updated], created: news.length, errors };
}
