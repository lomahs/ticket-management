import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { fixtures, renderApp, server } from './renderApp';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); });
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
    expect(within(screen.getByRole('region', { name: 'Status summary' })).getByText('3/12 function')).toBeTruthy();
    expect(screen.queryByText('Payment method selection')).toBeNull();
  });

  it('lists the phases of an anken, expands a phase\'s Tasks and opens the matrix of a phase', async () => {
    const phases = [
      { name: 'ITA', pct: 62, closed: 12, total: 20, start: '2026-10-17', end: '2026-11-30', spent_h: 31, remaining_h: 19 },
      { name: 'Other', pct: 0, closed: 0, total: 0, start: null, end: null, spent_h: 0, remaining_h: 0 },
    ];
    const tasks = [
      { key: 'PAY-1101', summary: '[Pay][LocalTask] ITA Prepare', name: 'ITA Prepare', phase: 'ITA', status: 'C', pct: 100, closed: 8, total: 8, start: '2026-10-17', end: '2026-10-20' },
      { key: 'PAY-1102', summary: '[Pay][LocalTask] ITA Execute', name: 'ITA Execute', phase: 'ITA', status: 'W', pct: 30, closed: 4, total: 12, start: '2026-10-21', end: '2026-11-30' },
    ];
    server.use(http.get('*/api/v1/anken/PAY-1100', () => HttpResponse.json({ card: { ...fixtures.anken[0], phases }, attention: [], tasks })));
    const { router } = renderApp('/anken/PAY-1100');
    const detail = await screen.findByRole('region', { name: 'PAY-1100 details' });
    expect(within(detail).getByText('12/20 sub-tasks done')).toBeTruthy();
    expect(within(detail).getByText('0/0 sub-tasks done')).toBeTruthy();
    expect(within(detail).queryByRole('link', { name: 'View Other as matrix' })).toBeNull();
    fireEvent.click(within(detail).getByRole('button', { name: 'Show tasks of ITA' }));
    const list = within(detail).getByRole('list', { name: 'Tasks of ITA' });
    expect(within(list).getByText('ITA Prepare')).toBeTruthy();
    expect(within(list).getByText('8/8 sub-tasks done')).toBeTruthy();
    expect(within(detail).getByRole('button', { name: 'Hide tasks of ITA' }).getAttribute('aria-expanded')).toBe('true');
    fireEvent.click(within(detail).getByRole('link', { name: 'View ITA as matrix' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/anken/PAY-1100/matrix'));
    expect(router.state.location.search).toMatchObject({ phase: 'ITA' });
  });

  it('points to Setting phase when the shown Tasks have no functions', async () => {
    const parents = [
      { key: 'PAY-1101', summary: '[Pay] Prepare', name: 'Prepare', phase: 'Prepare', group: 'Prepare', prefix: 'Prepare', workflow: true, functions: 0 },
      { key: 'PAY-1102', summary: '[Pay] Implement', name: 'Implement', phase: 'Implement', group: 'Implement', prefix: 'Implement', workflow: true, functions: 12 },
    ];
    server.use(http.get('*/api/v1/anken/PAY-1100/matrix', () => HttpResponse.json({ ...fixtures.matrix, functions: [], parents, task_keys: ['PAY-1101'] })));
    renderApp('/anken/PAY-1100/matrix?tasks=PAY-1101');
    expect(await screen.findByText(/The shown sub-phases have no functions yet/)).toBeTruthy();
    expect(screen.getAllByRole('link', { name: /Setting phase/ })[0].getAttribute('href')).toBe('/anken/PAY-1100/matrix/phases');
  });

  it('opens the first anken\'s matrix from the sidebar even when no anken has functions yet', async () => {
    const empty = { ...fixtures.matrix, functions: [], parents: [], task_keys: [] };
    server.use(
      http.get('*/api/v1/anken', () => HttpResponse.json(fixtures.anken.map((a) => ({ ...a, has_functions: false })))),
      http.get('*/api/v1/anken/PAY-1100/matrix', () => HttpResponse.json(empty)),
    );
    const { router } = renderApp('/matrix');
    expect(await screen.findByText(/No sub-tasks in this anken follow the naming rule yet/)).toBeTruthy();
    expect(router.state.location.pathname).toBe('/anken/PAY-1100/matrix');
  });

  it('writes matrix filter changes back to the URL', async () => {
    const { router } = renderApp('/anken/PAY-1100/matrix');
    fireEvent.change(await screen.findByLabelText('Search functions'), { target: { value: 'refund' } });
    await waitFor(() => expect(router.state.location.search).toMatchObject({ q: 'refund' }));
    expect(screen.getAllByText(/^1\/12 function/).length).toBeGreaterThan(0);
  });

  it('opens the ticket panel from ?ticket= and disables Jira edits without a writer', async () => {
    server.use(http.get('*/api/v1/meta', () => HttpResponse.json({ ...fixtures.meta, mode: 'jira', capabilities: { jira_write: false } })));
    renderApp('/my-work?member=binh&ticket=PAY-1213');
    const panel = await screen.findByRole('complementary', { name: 'Ticket PAY-1213' });
    await within(panel).findByText(/Domestic card payment – Fix comment BAL/);
    await waitFor(() => expect((within(panel).getByLabelText('Status') as HTMLSelectElement).disabled).toBe(true));
    expect(within(panel).getByText(/Updating Jira arrives in phase 2/)).toBeTruthy();
    expect(within(panel).getByRole('link', { name: /Domestic card payment – Fix comment BAL/ }).getAttribute('href'))
      .toBe('https://acme-pay.atlassian.net/browse/PAY-1213');
  });

  it('opens the ticket panel over the matrix when a cell is clicked', async () => {
    const { router } = renderApp('/anken/PAY-1100/matrix?members=binh');
    fireEvent.click(await screen.findByRole('button', { name: /^PAY-1213 / }));
    await screen.findByRole('complementary', { name: 'Ticket PAY-1213' });
    expect(router.state.location.pathname).toBe('/anken/PAY-1100/matrix');
    expect(router.state.location.search).toMatchObject({ members: 'binh', ticket: 'PAY-1213' });
  });

  it('shows one matrix per phase and a section per Task', async () => {
    const task = (key: string, name: string, group: string, functions: number) =>
      ({ key, summary: `[Pay][LocalTask] ${name}`, name, phase: group, group, prefix: group, workflow: true, functions });
    const parents = [task('PAY-1301', 'ITA Prepare', 'ITA', 1), task('PAY-1302', 'ITA Execute', 'ITA', 1), task('PAY-1303', 'UAT Execute', 'UAT', 1)];
    const [f1, f2, f3] = fixtures.matrix.functions;
    const rows = [{ ...f1, task_key: 'PAY-1301' }, { ...f2, task_key: 'PAY-1302' }, { ...f3, task_key: 'PAY-1303' }];
    server.use(http.get('*/api/v1/anken/PAY-1100/matrix', ({ request }) => {
      const keys = new URL(request.url).searchParams.getAll('tasks');
      const shown = keys.length ? keys : ['PAY-1301', 'PAY-1302'];
      return HttpResponse.json({ ...fixtures.matrix, parents, task_keys: shown, functions: rows.filter((r) => shown.includes(r.task_key)) });
    }));
    renderApp('/anken/PAY-1100/matrix');
    const ita = await screen.findByRole('region', { name: 'ITA matrix' });
    expect(within(ita).getAllByRole('rowgroup').map((g) => g.getAttribute('aria-label'))).toEqual(['ITA Prepare', 'ITA Execute']);
    expect(screen.queryByRole('region', { name: 'UAT matrix' })).toBeNull();
    cleanup();
    renderApp('/anken/PAY-1100/matrix?tasks=PAY-1301,PAY-1302,PAY-1303');
    expect(await screen.findByRole('region', { name: 'UAT matrix' })).toBeTruthy();
    expect(screen.getByRole('region', { name: 'ITA matrix' })).toBeTruthy();
    expect(screen.getByText(/from the link/)).toBeTruthy();
  });

  it('reorders phases and sub-phases by drag and drop and keeps the layout in this browser', async () => {
    const task = (key: string, name: string, group: string) =>
      ({ key, summary: `[Pay][LocalTask] ${name}`, name, phase: group, group, prefix: group, workflow: true, functions: 1 });
    const parents = [task('PAY-1301', 'ITA Prepare', 'ITA'), task('PAY-1302', 'ITA Execute', 'ITA'), task('PAY-1303', 'UAT Execute', 'UAT')];
    const asked: string[][] = [];
    server.use(http.get('*/api/v1/anken/PAY-1100/matrix', ({ request }) => {
      const shown = new URL(request.url).searchParams.getAll('shown');
      asked.push(shown);
      return HttpResponse.json({ ...fixtures.matrix, parents, task_keys: shown.length ? shown : ['PAY-1301', 'PAY-1302'], functions: [] });
    }));
    renderApp('/anken/PAY-1100/matrix/phases');
    const uat = await screen.findByRole('group', { name: 'UAT' });
    const ita = screen.getByRole('group', { name: 'ITA' });
    const dt = { setData: () => {}, effectAllowed: '' };
    fireEvent.dragStart(uat, { dataTransfer: dt });
    fireEvent.dragOver(ita, { dataTransfer: dt });
    fireEvent.drop(ita, { dataTransfer: dt });
    expect(screen.getAllByRole('group').map((g) => g.getAttribute('aria-label'))).toEqual(['UAT', 'ITA']);
    const execute = screen.getByRole('button', { name: /^ITA Execute/ });
    fireEvent.dragStart(execute, { dataTransfer: dt });
    fireEvent.drop(screen.getByRole('button', { name: /^UAT Execute/ }), { dataTransfer: dt }); // another phase: refused
    expect(screen.getAllByRole('button', { name: /Execute|Prepare/ }).map((b) => b.textContent)).toEqual(['UAT Execute1', 'ITA Prepare1', 'ITA Execute1']);
    fireEvent.dragOver(screen.getByRole('button', { name: /^ITA Prepare/ }), { dataTransfer: dt });
    fireEvent.drop(screen.getByRole('button', { name: /^ITA Prepare/ }), { dataTransfer: dt });
    expect(screen.getAllByRole('button', { name: /Execute|Prepare/ }).map((b) => b.textContent)).toEqual(['UAT Execute1', 'ITA Execute1', 'ITA Prepare1']);
    fireEvent.click(screen.getByRole('button', { name: /^UAT Execute/ })); // show it
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    const layout = { phases: ['UAT', 'ITA'], tasks: ['PAY-1303', 'PAY-1302', 'PAY-1301'], shown: ['PAY-1303', 'PAY-1302', 'PAY-1301'] };
    expect(JSON.parse(localStorage.getItem('tt.matrixLayout.PAY-1100') ?? 'null')).toEqual(layout);
    cleanup();
    renderApp('/anken/PAY-1100/matrix');
    await waitFor(() => expect(asked.at(-1)).toEqual(layout.shown)); // the Matrix asks for this browser's Tasks
    expect(await screen.findByText(/Showing/)).toBeTruthy();
    expect(screen.getByText('UAT, ITA')).toBeTruthy(); // in this browser's phase order
  });

  it('draws the effort burndown and writes the phase and member filters to the URL', async () => {
    const day = (date: string, plan: number, act: number | null) => ({ date, planned_h: 4, logged_h: 3, plan_remaining_h: plan, actual_remaining_h: act });
    let seen: URLSearchParams | undefined;
    server.use(http.get('*/api/v1/effort/burndown', ({ request }) => {
      seen = new URL(request.url).searchParams;
      return HttpResponse.json({
        epic_key: 'PAY-1100', title: 'Payment flow revision', phases: ['Prepare', 'Implement_Coding'], phase: seen.get('phase'),
        members: ['binh'], member: seen.get('member'), subtasks: 3, today: '2026-10-07',
        burndown: { estimate_h: 12, start: '2026-10-06', end: '2026-10-08', plan_remaining_h: 4, actual_remaining_h: 6,
          days: [day('2026-10-06', 8, 9), day('2026-10-07', 4, 6), day('2026-10-08', 0, null)] },
      });
    }));
    const { router } = renderApp('/effort/burndown');
    expect(await screen.findByText('Behind plan')).toBeTruthy();
    expect(screen.getByText('+2h')).toBeTruthy();
    expect(screen.getByRole('img', { name: /4h planned and 6h actually remaining of 12h/ })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Implement_Coding' }));
    await waitFor(() => expect(router.state.location.search).toMatchObject({ phase: 'Implement_Coding' }));
    fireEvent.change(screen.getByRole('combobox', { name: 'Member' }), { target: { value: 'binh' } });
    await waitFor(() => expect(seen?.get('member')).toBe('binh'));
    expect(seen?.get('phase')).toBe('Implement_Coding');
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

  it('maps tracker fields to Jira fields picked from the site\'s field list', async () => {
    const f = (key: string, label: string, expects: string, types: string[], field_id: string, mappable = true) =>
      ({ key, label, used_for: `${label} use`, expects, types, field_id, mappable });
    const view = {
      fields: [
        f('planned_start', 'Planned Start', 'Date', ['date', 'datetime'], 'customfield_10020'),
        f('planned_end', 'Planned End', 'Date', ['date', 'datetime'], 'customfield_10016'),
        f('effort', 'Planned effort', 'Hours', ['number'], 'timeoriginalestimate'),
        f('remaining', 'Remaining estimate', 'Hours', ['number'], 'timeestimate'),
        f('summary', 'Summary', 'Text', [], 'summary', false),
      ],
      catalog: [
        { id: 'customfield_10015', name: 'Start date', type: 'date' }, { id: 'customfield_10016', name: 'Planned End', type: 'date' },
        { id: 'customfield_10020', name: 'Planned Start', type: 'date' }, { id: 'summary', name: 'Summary', type: 'string' },
        { id: 'timeestimate', name: 'Remaining Estimate', type: 'number' }, { id: 'timeoriginalestimate', name: 'Original estimate', type: 'number' },
      ],
    };
    let saved: unknown;
    server.use(
      http.get('*/api/v1/settings/field-mapping', () => HttpResponse.json(view)),
      http.put('*/api/v1/settings/field-mapping', async ({ request }) => { saved = await request.json(); return HttpResponse.json(view); }),
    );
    renderApp('/settings/field-mapping');
    const start = await screen.findByLabelText('Planned Start Jira field') as HTMLSelectElement;
    expect(start.value).toBe('customfield_10020');
    const groups = [...start.querySelectorAll('optgroup')].map((g) => [g.label, g.querySelectorAll('option').length]);
    expect(groups).toEqual([['Date fields', 3], ['Other fields', 3]]);
    expect(screen.getByText('Jira system fields · always read')).toBeTruthy();
    expect(screen.queryByLabelText('Summary Jira field')).toBeNull();
    fireEvent.change(start, { target: { value: 'customfield_10015' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saved).toEqual({
      planned_start: 'customfield_10015', planned_end: 'customfield_10016', effort: 'timeoriginalestimate', remaining: 'timeestimate',
    }));
  });

  it('runs an incremental Jira sync from the sidebar', async () => {
    let body: unknown;
    server.use(http.post('*/api/v1/sync', async ({ request }) => { body = await request.json(); return HttpResponse.json(fixtures.meta.sync); }));
    renderApp('/my-work?member=binh');
    const sidebar = await screen.findByRole('complementary', { name: 'Sidebar' });
    fireEvent.click(within(sidebar).getByRole('button', { name: 'Sync Jira' }));
    await waitFor(() => expect(body).toEqual({ full: false }));
    expect(await screen.findByText('Synced with Jira')).toBeTruthy();
  });

  it('shows function names on Planning blocks and opens a function with today\'s tickets highlighted', async () => {
    const cells = fixtures.matrix.functions[2].cells.filter((c) => c !== null).map((c) => (c.key === 'PAY-1213' ? { ...c, planned_end: '2026-10-08' } : c));
    const dates = ['2026-10-05', '2026-10-06', '2026-10-07', '2026-10-08', '2026-10-09'];
    const lane = (member: string, keys: string[]) => ({
      member_id: member, days: dates.map((date) => ({ date, kind: null, label: null, tentative: false })),
      items: cells.filter((c) => keys.includes(c.key)).map((card) => ({ card, color_step: card.step })),
    });
    server.use(
      http.get('*/api/v1/planning', () => HttpResponse.json({
        start: '2026-10-05', end: '2026-10-11', projects: ['PAY'], capacity: [],
        days: dates.map((date) => ({ date, is_today: date === '2026-10-07', extra_note: null })),
        rows: [lane('binh', ['PAY-1213']), lane('tanaka', ['PAY-1214'])],
      })),
      http.get('*/api/v1/anken/PAY-1100/functions/F03', () => HttpResponse.json({
        epic_key: 'PAY-1100', function_id: 'F03', name: 'Domestic card payment', tickets: cells.map((card) => ({ phase: 'Implement_Coding', card })),
      })),
    );
    const { router } = renderApp('/planning?start=2026-10-05&weeks=1');
    const block = await screen.findByRole('button', { name: 'PAY-1213 F03 Domestic card payment' });
    expect(block.textContent).toBe('Domestic card payment');
    fireEvent.click(block);
    await waitFor(() => expect(router.state.location.search).toMatchObject({ fn: 'PAY-1100:F03' }));
    const panel = await screen.findByRole('complementary', { name: 'Function F03' });
    const list = await within(panel).findByRole('list', { name: 'Tickets by step' });
    expect(within(list).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual([
      'PAY-1211 Create', 'PAY-1212 BAL Review', 'PAY-1213 Fix comment BAL · to do today', 'PAY-1214 JM Review', 'PAY-1215 Fix comment JM',
    ]);
    expect(within(panel).getByText('1 to do today')).toBeTruthy();
    fireEvent.click(within(list).getByRole('button', { name: 'PAY-1213 Fix comment BAL · to do today' }));
    await waitFor(() => expect(router.state.location.search).toMatchObject({ ticket: 'PAY-1213' }));
    expect(router.state.location.search).not.toHaveProperty('fn');
  });

  it('falls back to the Jira tab for an unknown settings tab', async () => {
    const { router } = renderApp('/settings/bogus');
    await screen.findByRole('region', { name: 'Jira Cloud connection' });
    expect(router.state.location.pathname).toBe('/settings/jira');
  });
});
