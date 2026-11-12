import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import meBa from './fixtures/me-ba.json';
import { renderApp, server } from './renderApp';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); });
afterAll(() => server.close());

const row = (key: string, summary: string, extra: object = {}) => ({
  key, summary, status: 'W', assignee_id: 'an', reporter_id: 'minh', planned_start: '2026-10-05', planned_end: '2026-10-08', estimate_h: 8, step: null, ...extra,
});
const board = {
  epic: 'PAY-1100', title: 'Payment flow revision', project: 'PAY', site: 'https://acme-pay.atlassian.net',
  tasks: [{ task: row('PAY-1101', '[Prepare] Payment flow revision', { estimate_h: 0 }), phase: 'Prepare', workflow: false,
    subtasks: [row('PAY-1104', 'Kick-off with JM'), row('PAY-1105', 'Collect sample data', { assignee_id: 'binh' })] }],
  templates: [{ match: '[Prepare] Work requests', phase: 'Prepare', workflow: false, exists: false }],
};
const subtaskTypes = http.get('*/api/v1/tickets/subtask-types', () => HttpResponse.json({
  types: [{ id: '10004', name: 'Sub-bug', subtask: true }, { id: '10003', name: 'Sub-task', subtask: true }], default_id: '10003' }));

describe('Tickets', () => {
  it('keeps edits and new tasks as drafts, reviews the batch and commits exactly what was reviewed', async () => {
    let previewed: { updates: unknown[]; creates: { ref: string }[] } | undefined;
    let applied: { updates: unknown[]; creates: unknown[] } | undefined;
    server.use(
      http.get('*/api/v1/tickets/board', () => HttpResponse.json(board)),
      http.post('*/api/v1/tickets/batch/preview', async ({ request }) => {
        previewed = await request.json() as typeof previewed;
        return HttpResponse.json({
          updates: [{ key: 'PAY-1104', summary: 'Kick-off with JM', transition: null, jira_status: null, estimate_tracking: false,
            changes: [{ field: 'planned_end', jira_field: 'customfield_10016', before: '2026-10-08', after: '2026-10-09' }],
            requests: [{ method: 'PUT', path: '/rest/api/3/issue/PAY-1104', body: '{"fields":{"customfield_10016":"2026-10-09"}}' }] }],
          creates: [{ ref: previewed!.creates[0].ref, subtask: false, parent: 'PAY-1100', summary: '[Prepare] Work requests',
            request: { method: 'POST', path: '/rest/api/3/issue', body: '{"fields":{"summary":"[Prepare] Work requests"}}' } }],
        });
      }),
      http.post('*/api/v1/tickets/batch/apply', async ({ request }) => {
        applied = await request.json() as typeof applied;
        return HttpResponse.json({ updated: ['PAY-1104'], created: { x: 'PAY-1501' } });
      }),
    );
    renderApp('/tickets/PAY-1100');
    const table = await screen.findByRole('region', { name: 'Tickets' });
    fireEvent.change(within(table).getByLabelText('PAY-1104 end'), { target: { value: '2026-10-09' } });

    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    fireEvent.click(screen.getByRole('menuitem', { name: /^Tasks/ }));
    const dialog = await screen.findByRole('dialog', { name: 'New tasks' });
    fireEvent.click(within(dialog).getByLabelText('Add [Prepare] Work requests'));
    fireEvent.change(within(dialog).getByLabelText('Assignee'), { target: { value: 'chi' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add 1 task' }));
    expect(await screen.findByText('Not committed · 1 new · 1 updated')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Review & commit' }));
    const review = await screen.findByRole('dialog', { name: 'Commit to Jira' });
    expect(previewed?.updates).toEqual([{ key: 'PAY-1104', planned_end: '2026-10-09' }]);
    expect(within(review).queryByText('PUT /rest/api/3/issue/PAY-1104')).toBeNull();
    expect(within(review).getByText(/Task in PAY-1100 · Payment flow revision/)).toBeTruthy();
    fireEvent.click(within(review).getByRole('button', { name: 'Commit 2 requests' }));
    await waitFor(() => expect(applied?.updates).toEqual([{ key: 'PAY-1104',
      changes: [{ field: 'planned_end', before: '2026-10-08', after: '2026-10-09' }] }]));
    expect(screen.queryByRole('dialog', { name: 'Commit to Jira' })).toBeNull();
    expect(applied?.creates).toEqual(previewed?.creates);
    expect(await screen.findByText('No pending changes')).toBeTruthy();
    expect(await screen.findByText(/PAY-1100 is committed to Jira/)).toBeTruthy();
  });

  it('adds a sub-task as Sub-task, or as the type picked', async () => {
    let previewed: { creates: { issue_type_id?: string }[] } | undefined;
    server.use(
      http.get('*/api/v1/tickets/board', () => HttpResponse.json(board)),
      subtaskTypes,
      http.post('*/api/v1/tickets/batch/preview', async ({ request }) => {
        previewed = await request.json() as typeof previewed;
        return HttpResponse.json({ updates: [], creates: [] });
      }),
    );
    renderApp('/tickets/PAY-1100');
    const add = async (summary: string, type?: string) => {
      fireEvent.click(await screen.findByRole('button', { name: 'Create' }));
      fireEvent.click(screen.getByRole('menuitem', { name: /Sub-tasks/ }));
      const pick = await screen.findByRole('dialog', { name: 'Sub-tasks under a Task' });
      fireEvent.change(within(pick).getByLabelText('Under the Task'), { target: { value: 'PAY-1101' } });
      fireEvent.click(within(pick).getByRole('button', { name: 'Next' }));
      const dialog = await screen.findByRole('dialog', { name: 'Add sub-tasks' });
      await waitFor(() => expect((within(dialog).getByLabelText('Type') as HTMLSelectElement).value).toBe('10003'));
      if (type) fireEvent.change(within(dialog).getByLabelText('Type'), { target: { value: type } });
      fireEvent.change(within(dialog).getByLabelText('Summary'), { target: { value: summary } });
      fireEvent.change(within(dialog).getByLabelText('Assignee'), { target: { value: 'an' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'Add sub-task' }));
    };
    await add('Collect refunds');
    await add('Refund rounding', '10004');
    fireEvent.click(await screen.findByRole('button', { name: 'Review & commit' }));
    await waitFor(() => expect(previewed?.creates.map((c) => c.issue_type_id)).toEqual(['10003', '10004']));
  });

  it('adds 3-step functions under a workflow Task', async () => {
    const bodies: { steps?: number; jm?: string | null }[] = [];
    const wf = { ...board, tasks: [{ task: row('PAY-1102', '[Implement_Coding] Payment flow', { estimate_h: 0 }), phase: 'Implement_Coding',
      workflow: true, subtasks: [] }] };
    server.use(
      http.get('*/api/v1/tickets/board', () => HttpResponse.json(wf)),
      subtaskTypes,
      http.post('*/api/v1/anken/PAY-1100/functions/preview', async ({ request }) => {
        bodies.push(await request.json() as { steps?: number });
        return HttpResponse.json({ phase: 'Implement_Coding', function_id: 'F13', prefix: 'PAY', steps: [] });
      }),
    );
    renderApp('/tickets/PAY-1100');
    fireEvent.click(await screen.findByRole('button', { name: 'Create' }));
    fireEvent.click(screen.getByRole('menuitem', { name: /Sub-tasks/ }));
    const pick = await screen.findByRole('dialog', { name: 'Sub-tasks under a Task' });
    fireEvent.change(within(pick).getByLabelText('Under the Task'), { target: { value: 'PAY-1102' } });
    fireEvent.click(within(pick).getByRole('button', { name: 'Next' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add sub-tasks' });
    // no role queries in this dialog: jsdom cannot clone its segment buttons' var() backgrounds
    expect(within(dialog).getByText('Single sub-task')).toBeTruthy();
    fireEvent.click(within(dialog).getByText('Function · 3 steps'));
    expect(within(dialog).queryByLabelText('Reviewer JM')).toBeNull();
    fireEvent.change(within(dialog).getByLabelText(/^Function names/), { target: { value: 'Execute test case' } });
    await waitFor(() => expect(bodies.at(-1)).toMatchObject({ steps: 3, name: 'Execute test case' }));
  });

  it('hands selected tickets to Jira and shows everything read-only to a non-admin', async () => {
    server.use(
      http.get('*/api/v1/me', () => HttpResponse.json(meBa)),
      http.get('*/api/v1/tickets/board', () => HttpResponse.json(board)),
    );
    renderApp('/tickets/PAY-1100');
    const table = await screen.findByRole('region', { name: 'Tickets' });
    expect((within(table).getByLabelText('PAY-1104 status') as HTMLSelectElement).disabled).toBe(true);
    expect(screen.queryByRole('button', { name: 'Create' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Import CSV' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Review & commit' })).toBeNull();
    fireEvent.click(within(table).getByLabelText('Select PAY-1104'));
    fireEvent.click(within(table).getByLabelText('Select PAY-1105'));
    const bar = screen.getByRole('toolbar', { name: 'Selected tickets' });
    expect(within(bar).getByRole('link', { name: /Open in Jira/ }).getAttribute('href'))
      .toBe(`https://acme-pay.atlassian.net/issues/?jql=${encodeURIComponent('key in (PAY-1104, PAY-1105) ORDER BY key')}`);
    expect(within(bar).queryByLabelText('Set status…')).toBeNull();
  });

  it('lists sub-tasks flat and sorts them by several columns', async () => {
    const flat = { ...board, tasks: [{ ...board.tasks[0], subtasks: [
      row('PAY-1104', 'Kick-off', { assignee_id: 'binh', planned_start: '2026-10-07' }), row('PAY-1105', 'Collect', { assignee_id: 'an', planned_start: '2026-10-09' }),
      row('PAY-1106', 'Minutes', { assignee_id: 'an', planned_start: '2026-10-05' }),
    ] }] };
    server.use(http.get('*/api/v1/tickets/board', () => HttpResponse.json(flat)));
    renderApp('/tickets/PAY-1100');
    const table = await screen.findByRole('region', { name: 'Tickets' });
    const order = () => within(table).getAllByRole('button', { name: /^PAY-/ }).map((b) => b.textContent);
    expect(order()).toEqual(['PAY-1104', 'PAY-1105', 'PAY-1106']);
    fireEvent.click(within(table).getByRole('button', { name: 'Sort by Assignee' }));
    fireEvent.click(within(table).getByRole('button', { name: 'Sort by Start' }), { shiftKey: true });
    await waitFor(() => expect(order()).toEqual(['PAY-1106', 'PAY-1105', 'PAY-1104']));
    fireEvent.click(within(table).getByRole('button', { name: 'Sort by Start' }), { shiftKey: true });
    await waitFor(() => expect(order()).toEqual(['PAY-1105', 'PAY-1106', 'PAY-1104']));
    expect(screen.getByText('Sorted by')).toBeTruthy();
  });

  it('imports a CSV of the template as drafts, shows what changed and reviews it with the summary', async () => {
    let previewed: { updates: unknown[]; creates: unknown[] } | undefined;
    server.use(
      http.get('*/api/v1/tickets/board', () => HttpResponse.json(board)),
      http.post('*/api/v1/tickets/batch/preview', async ({ request }) => {
        previewed = await request.json() as typeof previewed;
        return HttpResponse.json({ updates: [], creates: [] });
      }),
    );
    renderApp('/tickets/PAY-1100');
    const table = await screen.findByRole('region', { name: 'Tickets' });
    const csv = ['Key;Issue Type;Parent;Summary;Status;Assignee;Reporter;Start;End;Estimate (h)',
      'PAY-1104;Sub-task;PAY-1101;Kick-off with the JM;;;;;2026/10/09;',
      ';Sub-task;PAY-1101;Write the minutes;;an.nguyen@acme.vn;;;;',
      'PAY-4040;;;;;;;;;'].join('\n');
    fireEvent.change(screen.getByLabelText('CSV file to import'), { target: { files: [new File([csv], 'tickets.csv', { type: 'text/csv' })] } });
    expect(await screen.findByText('Not committed · 1 new · 1 updated')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Changed only · 2' }).getAttribute('aria-pressed')).toBe('true');
    expect(within(table).queryByRole('button', { name: 'PAY-1105' })).toBeNull();
    expect((within(table).getByLabelText('PAY-1104 summary') as HTMLInputElement).value).toBe('Kick-off with the JM');
    expect(within(table).getByText('CHANGED')).toBeTruthy();
    expect(within(table).getByText('NEW')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Import skipped 1 row' }));
    const skipped = await screen.findByRole('dialog', { name: 'Rows not imported' });
    expect(within(skipped).getByText('PAY-4040 is not a ticket of PAY-1100')).toBeTruthy();
    fireEvent.click(within(skipped).getByRole('button', { name: 'Close' }));

    fireEvent.click(screen.getByRole('button', { name: 'Review & commit' }));
    await waitFor(() => expect(previewed?.updates).toEqual([{ key: 'PAY-1104', summary: 'Kick-off with the JM', planned_end: '2026-10-09' }]));
    expect(previewed?.creates).toMatchObject([{ subtask: true, parent_key: 'PAY-1101', summary: 'Write the minutes', assignee_id: 'an' }]);
  });
});
