import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { fixtures, renderApp, server } from './renderApp';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); });
afterAll(() => server.close());

const card = (key: string, summary: string, assignee: string | null, status = 'W') => ({ ...fixtures.ticket.card, key, summary, assignee_id: assignee, status });

describe('single tickets and Ticket Check', () => {
  it('lists single tickets that share a name as rows of their own on the matrix', async () => {
    const parents = [{ key: 'PAY-1160', summary: '[Prepare] Requirement study', name: 'Requirement study', phase: 'Prepare', group: 'Prepare', prefix: 'Prepare', workflow: true, functions: 0 }];
    const tickets = ['an', 'binh'].map((who, i) => ({ task_key: 'PAY-1160', name: 'Requirement study', card: card(`PAY-116${i + 1}`, '[Prepare][ST] Requirement study', who, 'C') }));
    server.use(http.get('*/api/v1/anken/PAY-1100/matrix', () => HttpResponse.json({ ...fixtures.matrix, functions: [], parents, task_keys: ['PAY-1160'], tickets })));
    renderApp('/anken/PAY-1100/matrix?tasks=PAY-1160');
    const group = await screen.findByRole('rowgroup', { name: 'Requirement study' });
    expect(within(group).getAllByText('Single ticket')).toHaveLength(2);
    expect(within(group).getByText('0/0 function · 2/2 single ticket')).toBeTruthy();
    expect(within(group).getByRole('button', { name: 'PAY-1161 Closed' })).toBeTruthy();
    expect(within(group).getByRole('button', { name: 'PAY-1162 Closed' })).toBeTruthy();
  });

  it('counts each check, filters by one from the URL and opens a ticket', async () => {
    const view = {
      checked: 40,
      counts: [{ check: 'rule', count: 1 }, { check: 'no_end', count: 1 }, { check: 'no_labels', count: 2 }],
      rows: [
        { card: card('PAY-1104', 'Kick-off with JM', 'minh'), epic_key: 'PAY-1100', task_key: 'PAY-1101', task_name: 'Payment flow revision', problems: ['rule', 'no_labels'] },
        { card: card('PAY-1213', '[Implement_Coding][F03] Domestic card payment – Fix comment BAL', null), epic_key: 'PAY-1100', task_key: 'PAY-1102', task_name: 'Payment flow revision', problems: ['no_end', 'no_labels'] },
      ],
    };
    let asked: string | null | undefined;
    server.use(http.get('*/api/v1/ticket-check', ({ request }) => { asked = new URL(request.url).searchParams.get('anken'); return HttpResponse.json(view); }));
    const { router } = renderApp('/ticket-check');
    const list = await screen.findByRole('region', { name: 'Tickets to fix' });
    expect(asked).toBeNull(); // "All my anken"
    expect(within(list).getByText('2 of 40 sub-tasks need a fix')).toBeTruthy();
    expect(within(list).getAllByText('No labels')).toHaveLength(2);
    fireEvent.click(within(screen.getByRole('group', { name: 'Checks' })).getByRole('button', { name: /Name outside the rule/ }));
    await waitFor(() => expect(router.state.location.search).toMatchObject({ check: 'rule' }));
    expect(within(list).queryByText('PAY-1213')).toBeNull();
    fireEvent.click(within(list).getByRole('button', { name: 'PAY-1104' }));
    await waitFor(() => expect(router.state.location.search).toMatchObject({ ticket: 'PAY-1104' }));
  });
});
