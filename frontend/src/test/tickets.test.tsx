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

    fireEvent.click(screen.getByRole('button', { name: 'New tasks' }));
    const dialog = await screen.findByRole('dialog', { name: 'New tasks' });
    fireEvent.click(within(dialog).getByLabelText('Add [Prepare] Work requests'));
    fireEvent.change(within(dialog).getByLabelText('Assignee'), { target: { value: 'chi' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add 1 task' }));
    expect(await screen.findByText('Not committed · 1 new · 1 updated')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Review & commit' }));
    const review = await screen.findByRole('dialog', { name: 'Commit to Jira' });
    expect(previewed?.updates).toEqual([{ key: 'PAY-1104', planned_end: '2026-10-09' }]);
    expect(within(review).getByText('PUT /rest/api/3/issue/PAY-1104')).toBeTruthy();
    expect(within(review).getByText(/Task in PAY-1100 · Payment flow revision/)).toBeTruthy();
    fireEvent.click(within(review).getByRole('button', { name: 'Commit 2 requests' }));
    await waitFor(() => expect(applied?.updates).toEqual([{ key: 'PAY-1104',
      changes: [{ field: 'planned_end', before: '2026-10-08', after: '2026-10-09' }] }]));
    expect(applied?.creates).toEqual(previewed?.creates);
    expect(await screen.findByText('No pending changes')).toBeTruthy();
  });

  it('hands selected tickets to Jira and shows everything read-only to a non-admin', async () => {
    server.use(
      http.get('*/api/v1/me', () => HttpResponse.json(meBa)),
      http.get('*/api/v1/tickets/board', () => HttpResponse.json(board)),
    );
    renderApp('/tickets/PAY-1100');
    const table = await screen.findByRole('region', { name: 'Tickets' });
    expect((within(table).getByLabelText('PAY-1104 status') as HTMLSelectElement).disabled).toBe(true);
    expect(screen.queryByRole('button', { name: 'New tasks' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Review & commit' })).toBeNull();
    fireEvent.click(within(table).getByLabelText('Select PAY-1104'));
    fireEvent.click(within(table).getByLabelText('Select PAY-1105'));
    const bar = screen.getByRole('toolbar', { name: 'Selected tickets' });
    expect(within(bar).getByRole('link', { name: /Open in Jira/ }).getAttribute('href'))
      .toBe(`https://acme-pay.atlassian.net/issues/?jql=${encodeURIComponent('key in (PAY-1104, PAY-1105) ORDER BY key')}`);
    expect(within(bar).queryByLabelText('Set status…')).toBeNull();
  });
});
