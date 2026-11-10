import { describe, expect, it } from 'vitest';
import type { Schemas } from '../../../shared/api/types';
import { parseCsv } from '../../../shared/format/csv';
import { exportRows, importCsv, TEMPLATE, type ImportContext } from './csv';
import { itemsOf } from './list';

const row = (key: string, extra: Partial<Schemas['BoardRow']> = {}): Schemas['BoardRow'] => ({
  key, summary: `Summary ${key}`, status: 'W', assignee_id: 'an', reporter_id: 'minh', planned_start: '2026-10-05', planned_end: '2026-10-08',
  estimate_h: 8, step: null, ...extra,
});
const task = row('PAY-1101', { summary: '[Prepare] Payment' });
const board: Schemas['TicketBoard'] = {
  epic: 'PAY-1100', title: 'Payment', project: 'PAY', site: null, templates: [],
  tasks: [{ task, phase: 'Prepare', workflow: false, subtasks: [row('PAY-1104'), row('PAY-1105', { assignee_id: 'gone' })] }],
};
const member = (id: string, email: string) => ({ id, name: id, email } as Schemas['Member']);
const ctx: ImportContext = {
  epic: 'PAY-1100', rows: { 'PAY-1101': task, 'PAY-1104': board.tasks[0].subtasks[0], 'PAY-1105': board.tasks[0].subtasks[1] },
  tasks: new Set(['PAY-1101']), members: [member('an', 'an@acme.vn'), member('binh', 'binh@acme.vn'), member('minh', 'minh@acme.vn')],
};
const file = (sep: string, ...lines: string[][]) => [TEMPLATE, ...lines].map((l) => l.join(sep)).join('\r\n');
const email = (id: string) => ctx.members.find((m) => m.id === id)?.email || id;

describe('the ticket CSV', () => {
  it('exports what the list shows, edits included, in the template', () => {
    const items = itemsOf(board, [{ ref: 'n', subtask: true, parent_key: 'PAY-1101', summary: 'New one', assignee_id: 'binh', reporter_id: '',
      planned_start: '2026-10-12', planned_end: '', estimate_h: '2' }]);
    expect(exportRows(items, { 'PAY-1104': { status: 'R', planned_end: '2026-10-09' } }, 'PAY-1100', email)).toEqual([
      ['PAY-1101', 'Task', 'PAY-1100', '[Prepare] Payment', 'WIP', 'an@acme.vn', 'minh@acme.vn', '2026/10/05', '2026/10/08', '8'],
      ['PAY-1104', 'Sub-task', 'PAY-1101', 'Summary PAY-1104', 'Resolved', 'an@acme.vn', 'minh@acme.vn', '2026/10/05', '2026/10/09', '8'],
      ['PAY-1105', 'Sub-task', 'PAY-1101', 'Summary PAY-1105', 'WIP', 'gone', 'minh@acme.vn', '2026/10/05', '2026/10/08', '8'],
      ['', 'Sub-task', 'PAY-1101', 'New one', 'NYS', 'binh@acme.vn', '', '2026/10/12', '', '2'],
    ]);
  });

  it('updates the tickets with a key and leaves empty cells and Jira’s values alone', () => {
    const r = importCsv(file(';',
      ['PAY-1104', 'Sub-task', 'PAY-1101', 'Renamed', 'closed', 'BINH@acme.vn', '', '2026/10/06', '', '7,5'],
      ['pay-1105', 'Sub-task', 'PAY-1101', 'Summary PAY-1105', 'WIP', 'gone', 'minh@acme.vn', '2026/10/05', '2026/10/08', '8'],
    ), ctx, { 'PAY-1104': { planned_end: '2026-10-20' }, 'PAY-1101': { status: 'R' } });
    expect(r).toMatchObject({ ok: true, updated: ['PAY-1104'], created: 0, errors: [] });
    expect(r.ok && r.edits).toEqual({
      'PAY-1104': { planned_end: '2026-10-20', summary: 'Renamed', status: 'C', assignee_id: 'binh', planned_start: '2026-10-06', estimate_h: '7.5' },
      'PAY-1101': { status: 'R' },
    });
  });

  it('adds new Tasks and sub-tasks from rows without a key', () => {
    const r = importCsv(file(',',
      ['', 'Sub-task', 'PAY-1101', 'Check refunds', '', 'an@acme.vn', 'minh@acme.vn', '2026/10/12', '2026/10/13', '4'],
      ['', 'Task', '', '[Prepare] Work requests', '', 'binh@acme.vn', '', '', '', ''],
    ), ctx, {});
    expect(r.ok && r.news.map(({ ref: _, ...n }) => n)).toEqual([
      { subtask: true, parent_key: 'PAY-1101', summary: 'Check refunds', assignee_id: 'an', reporter_id: 'minh', planned_start: '2026-10-12',
        planned_end: '2026-10-13', estimate_h: '4' },
      { subtask: false, summary: '[Prepare] Work requests', assignee_id: 'binh', reporter_id: '', planned_start: '', planned_end: '', estimate_h: '' },
    ]);
  });

  it('skips each row with a mistake and tells why, keeping the others', () => {
    const r = importCsv(file(',',
      ['PAY-9999', '', '', '', '', '', '', '', '', ''],
      ['PAY-1104', '', '', '', 'Done', 'nobody@acme.vn', '', '10/06/2026', '', ''],
      ['PAY-1101', '', '', '', '', '', '', '2026/10/20', '2026/10/10', ''],
      ['', 'Sub-task', 'PAY-1100', '', '', '', '', '', '', ''],
      ['PAY-1105', '', '', 'Renamed', '', '', '', '', '', ''],
      ['PAY-1105', '', '', 'Again', '', '', '', '', '', ''],
    ), ctx, {});
    expect(r.ok && r.errors).toEqual([
      { line: 2, key: 'PAY-9999', message: 'PAY-9999 is not a ticket of PAY-1100' },
      { line: 3, key: 'PAY-1104', message: 'Status: Done is not one of NYS, WIP, Resolved, Closed · Assignee: no member has the email nobody@acme.vn · Start: 10/06/2026 is not a date (yyyy/MM/dd)' },
      { line: 4, key: 'PAY-1101', message: 'End is before Start' },
      { line: 5, key: '', message: 'Parent: PAY-1100 is not a Task of PAY-1100 · Summary is required for a new ticket · Assignee is required for a new ticket' },
      { line: 7, key: 'PAY-1105', message: 'PAY-1105 is in the file more than once' },
    ]);
    expect(r.ok && r.edits).toEqual({ 'PAY-1105': { summary: 'Renamed' } });
  });

  it('refuses a file that is not the template', () => {
    expect(importCsv('Issue key,Summary,Status\nPAY-1104,x,WIP', ctx, {})).toEqual({ ok: false,
      message: 'Not the ticket template · missing Key, Issue Type, Parent, Assignee, Reporter, Start, End, Estimate (h) · unknown Issue key' });
  });

  it('reads quoted cells with separators, quotes and line breaks', () => {
    expect(parseCsv('﻿a;b\r\n"x;1";"say ""hi""\nthere"\n\n').records).toEqual([['a', 'b'], ['x;1', 'say "hi"\nthere']]);
  });
});
