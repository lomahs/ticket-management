import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { fixtures, renderApp, server } from './renderApp';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); });
afterAll(() => server.close());

describe('screens render API data at their URLs', () => {
  it('redirects / to My Work and shows the member\'s deadlines', async () => {
    const { router } = renderApp('/?ticket=');
    expect(await screen.findByRole('heading', { name: 'My Work' })).toBeTruthy();
    expect(router.state.location.pathname).toBe('/my-work');
    const delay = await screen.findByRole('region', { name: 'Delay task' });
    expect(within(delay).getByText('PAY-1012')).toBeTruthy();
    expect(within(screen.getByRole('region', { name: 'Your backlog' })).getByText('Overdue')).toBeTruthy();
  });

  it('applies matrix filters from the URL', async () => {
    renderApp('/anken/PAY-1100/matrix?members=binh');
    expect(await screen.findByText('Domestic card payment')).toBeTruthy();
    expect(screen.getByText('3/12 function')).toBeTruthy();
    expect(screen.queryByText('Payment method selection')).toBeNull();
  });

  it('writes matrix filter changes back to the URL', async () => {
    const { router } = renderApp('/anken/PAY-1100/matrix');
    fireEvent.change(await screen.findByLabelText('Search functions'), { target: { value: 'refund' } });
    await waitFor(() => expect(router.state.location.search).toMatchObject({ q: 'refund' }));
    expect(screen.getByText('1/12 function')).toBeTruthy();
  });

  it('opens the ticket panel from ?ticket= and disables Jira edits without a writer', async () => {
    server.use(http.get('*/api/v1/meta', () => HttpResponse.json({ ...fixtures.meta, mode: 'jira', capabilities: { jira_write: false } })));
    renderApp('/my-work?member=binh&ticket=PAY-1213');
    const panel = await screen.findByRole('complementary', { name: 'Ticket PAY-1213' });
    await within(panel).findByText(/Domestic card payment – Fix comment BAL/);
    await waitFor(() => expect((within(panel).getByLabelText('Status') as HTMLSelectElement).disabled).toBe(true));
    expect(within(panel).getByText(/Updating Jira arrives in phase 2/)).toBeTruthy();
  });

  it('switches the matrix to another Task through the URL', async () => {
    const { router } = renderApp('/anken/PAY-1100/matrix?members=binh');
    const select = await screen.findByLabelText('Task');
    expect((select as HTMLSelectElement).value).toBe('PAY-1102');
    fireEvent.change(select, { target: { value: 'PAY-1101' } });
    await waitFor(() => expect(router.state.location.search).toMatchObject({ task: 'PAY-1101' }));
    expect(router.state.location.search).not.toHaveProperty('members'); // filters belong to the Task that was shown
  });

  it('shows API errors instead of a blank screen', async () => {
    server.use(http.get('*/api/v1/my-work', () => HttpResponse.json(
      { code: 'not_found', detail: 'Member zz was not found', errors: [] }, { status: 404, headers: { 'Content-Type': 'application/problem+json' } })));
    renderApp('/my-work?member=zz');
    expect((await screen.findByRole('alert')).textContent).toBe('Member zz was not found');
  });

  it('edits phases and Task templates and saves them', async () => {
    const view = {
      phases: ['UC', 'FUT'],
      tasks: [{ match: 'FUT create test case', phase: 'FUT', prefix: 'FUTTC', workflow: true }],
      seen: [
        { key: 'PAY-1110', epic_key: 'PAY-1100', summary: 'FUT create test case', phase: 'FUT', template: 0 },
        { key: 'PAY-1113', epic_key: 'PAY-1100', summary: 'Work Request management', phase: '', template: null },
      ],
    };
    let saved: unknown;
    server.use(
      http.get('*/api/v1/settings/structure', () => HttpResponse.json(view)),
      http.post('*/api/v1/settings/structure/preview', () => HttpResponse.json(view)),
      http.put('*/api/v1/settings/structure', async ({ request }) => { saved = await request.json(); return HttpResponse.json(view); }),
    );
    renderApp('/settings/structure');
    const jira = await screen.findByRole('region', { name: 'Tasks in Jira' });
    expect(within(jira).getByText('No match')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Add phase' }));
    fireEvent.change(screen.getByLabelText('Phase 3'), { target: { value: 'ITA' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saved).toEqual({ phases: ['UC', 'FUT', 'ITA'], tasks: view.tasks }));
  });

  it('falls back to the Jira tab for an unknown settings tab', async () => {
    const { router } = renderApp('/settings/bogus');
    await screen.findByRole('region', { name: 'Jira Cloud connection' });
    expect(router.state.location.pathname).toBe('/settings/jira');
  });
});
