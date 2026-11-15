import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { renderApp, server } from './renderApp';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); vi.restoreAllMocks(); });
afterAll(() => server.close());

const ONB = { epic_key: 'PAY-1100', title: 'Payment flow revision', anken_prefix: 'PAY', labels: [] as string[], step: 3, started_at: '2026-10-05T09:00:00Z',
  started_by: 'minh.hoang@acme.vn', saved_at: '2026-10-07T09:00:00Z', saved_by: 'minh.hoang@acme.vn', finished_at: null, draft: false };
const DRAFT = { ...ONB, epic_key: 'draft-1', title: 'Refund automation', anken_prefix: '', step: 2, draft: true };
const PEOPLE = [
  { member_id: 'an', name: 'An', role: 'BA', country: 'VN', in_anken: true, hours_per_day: 8, elsewhere: [], working_day_h: 8 },
  { member_id: 'minh', name: 'Minh', role: 'BAL', country: 'VN', in_anken: true, hours_per_day: null,
    elsewhere: [{ epic_key: 'PAY-1300', title: 'Refund', hours: 4 }], working_day_h: 8 },
  { member_id: 'tanaka', name: 'Tanaka', role: 'JM', country: 'JP', in_anken: false, hours_per_day: null, elsewhere: [], working_day_h: 8 },
];
const TASK = { key: 'PAY-1102', summary: '[PAY][LocalTask] ITA Create test case', name: 'ITA Create test case', phase: 'ITA' };
const EXECUTE = { key: 'PAY-1103', summary: '[PAY][LocalTask] ITA Execute', name: 'ITA Execute', phase: 'ITA' };
const LOGIN = { id: 1, epic_key: 'PAY-1100', position: 0, phase: 'ITA', subphase: 'Create test case', function_name: 'Login', steps: 5, effort_h: 8,
  ba_id: null, bal_id: null, jm_id: null, task_key: 'PAY-1102', pushed_at: null };
const RUN = { ...LOGIN, id: 2, position: 1, subphase: 'Execute', function_name: 'Run', steps: null, effort_h: 16, ba_id: 'an' };

/** The onboarding record and its members, plus whatever the test adds; progress saves are recorded. */
function onboarding(over: Partial<typeof ONB> = {}) {
  const progress: unknown[] = [];
  server.use(
    http.get('*/api/v1/onboarding/PAY-1100', () => HttpResponse.json({ ...ONB, ...over })),
    http.get('*/api/v1/onboarding/PAY-1100/members', () => HttpResponse.json(PEOPLE)),
    http.put('*/api/v1/onboarding/PAY-1100', async ({ request }) => {
      const b = await request.json() as { step: number };
      progress.push(b);
      return HttpResponse.json({ ...ONB, ...over, step: Math.max(ONB.step, b.step) });
    }),
  );
  return progress;
}

describe('onboarding', () => {
  it('ticks only the steps the anken went past, not those skipped by opening a later one', async () => {
    onboarding({ step: 2 });
    renderApp('/onboarding/PAY-1100/members');
    const band = await screen.findByRole('region', { name: 'Onboarding progress' });
    await within(band).findByText('Payment flow revision');
    expect(within(band).getAllByRole('listitem').map((s) => s.textContent?.startsWith('✓'))).toEqual([true, false, false, false, false, false]);
  });

  it('lists the ankens in progress and resumes one at its step', async () => {
    let deleted = false;
    server.use(
      http.get('*/api/v1/onboarding', () => HttpResponse.json([ONB, { ...ONB, epic_key: 'CRM-450', title: 'Campaigns', step: 6, finished_at: '2026-10-06T09:00:00Z' }])),
      http.delete('*/api/v1/onboarding/PAY-1100', () => { deleted = true; return new HttpResponse(null, { status: 204 }); }),
    );
    onboarding();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderApp('/onboarding');
    const open = await screen.findByRole('region', { name: 'In progress' });
    expect(within(open).getByText('Payment flow revision')).toBeTruthy();
    expect(within(open).getByText('Step 3 of 6 ·')).toBeTruthy();
    expect(within(screen.getByRole('region', { name: 'Finished' })).getByText('Campaigns')).toBeTruthy();
    fireEvent.click(within(open).getByRole('button', { name: 'Discard PAY-1100' }));
    await waitFor(() => expect(deleted).toBe(true));
  });

  it('looks an epic up and tracks it', async () => {
    let started: unknown = null;
    server.use(
      http.get('*/api/v1/onboarding/lookup', ({ request }) => HttpResponse.json({ key: new URL(request.url).searchParams.get('key'), title: 'Refund automation', project: 'PAY', tracked: false, onboarding: false })),
      http.post('*/api/v1/onboarding', async ({ request }) => { started = await request.json(); return HttpResponse.json({ ...ONB, epic_key: 'PAY-1500', step: 2 }); }),
      http.get('*/api/v1/onboarding/PAY-1500', () => HttpResponse.json({ ...ONB, epic_key: 'PAY-1500', step: 2 })),
      http.get('*/api/v1/onboarding/PAY-1500/tasks', () => HttpResponse.json({ anken_prefix: '', task_rule: '[{anken_prefix}][{os_task}] {sub_phase}', tasks: [] })),
      http.get('*/api/v1/onboarding/PAY-1500/members', () => HttpResponse.json(PEOPLE)),
    );
    const { router } = renderApp('/onboarding/new');
    fireEvent.change(await screen.findByLabelText('Epic key'), { target: { value: 'pay-1500' } });
    fireEvent.click(screen.getByRole('button', { name: 'Look up in Jira' }));
    expect(await screen.findByText('Refund automation')).toBeTruthy();
    expect(screen.getByText('Not yet')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Track & continue' }));
    await waitFor(() => expect(started).toEqual({ key: 'PAY-1500' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/onboarding/PAY-1500/tasks'));
  });

  it('starts an anken without its epic from its name', async () => {
    let started: unknown = null;
    server.use(
      http.post('*/api/v1/onboarding/draft', async ({ request }) => { started = await request.json(); return HttpResponse.json(DRAFT); }),
      http.get('*/api/v1/onboarding/draft-1', () => HttpResponse.json(DRAFT)),
      http.get('*/api/v1/onboarding/draft-1/tasks', () => HttpResponse.json({ anken_prefix: '', task_rule: '[{anken_prefix}][{os_task}] {sub_phase}', tasks: [] })),
      http.get('*/api/v1/onboarding/draft-1/members', () => HttpResponse.json(PEOPLE)),
    );
    const { router } = renderApp('/onboarding/new');
    fireEvent.click(await screen.findByRole('radio', { name: /No, not yet/ }));
    expect(screen.queryByLabelText('Epic key')).toBeNull();
    const start = screen.getByRole('button', { name: 'Start without epic' }) as HTMLButtonElement;
    expect(start.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Anken name'), { target: { value: ' Refund automation ' } });
    fireEvent.click(start);
    await waitFor(() => expect(started).toEqual({ name: 'Refund automation' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/onboarding/draft-1/tasks'));
  });

  it('shows an anken without its epic by its name, and its Tasks wait for the epic', async () => {
    server.use(
      http.get('*/api/v1/onboarding', () => HttpResponse.json([DRAFT])),
      http.get('*/api/v1/onboarding/draft-1', () => HttpResponse.json({ ...DRAFT, anken_prefix: 'RFD' })),
      http.get('*/api/v1/onboarding/draft-1/members', () => HttpResponse.json(PEOPLE)),
      http.get('*/api/v1/onboarding/draft-1/tasks', () => HttpResponse.json({ anken_prefix: 'RFD', task_rule: '[{anken_prefix}][{os_task}] {sub_phase}', tasks: [
        { match: '[*][LocalTask] ITA Prepare', phase: 'ITA', existing_key: null, name: '[RFD][LocalTask] ITA Prepare', recognised: true },
      ] })),
    );
    renderApp('/onboarding');
    const open = await screen.findByRole('region', { name: 'In progress' });
    expect(within(open).getByText('No epic yet')).toBeTruthy();
    expect(within(open).getByRole('button', { name: 'Resume Refund automation' })).toBeTruthy();
    cleanup();
    renderApp('/onboarding/draft-1/tasks');
    expect(await screen.findByText('[RFD][LocalTask] ITA Prepare')).toBeTruthy();
    expect(screen.getByText('1 Tasks, created once the epic is linked')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Review/ })).toBeNull();
    expect(within(screen.getByRole('region', { name: 'Onboarding progress' })).getByRole('link', { name: 'Link epic' })).toBeTruthy();
  });

  it('links the epic of an anken planned without it, then opens Setting Phase under the epic', async () => {
    let linked: unknown = null;
    server.use(
      http.get('*/api/v1/onboarding/draft-1', () => HttpResponse.json(DRAFT)),
      http.get('*/api/v1/onboarding/lookup', ({ request }) => HttpResponse.json({ key: new URL(request.url).searchParams.get('key'), title: 'Refund automation', project: 'PAY', tracked: false, onboarding: false })),
      http.post('*/api/v1/onboarding/draft-1/link', async ({ request }) => { linked = await request.json(); return HttpResponse.json({ ...DRAFT, epic_key: 'PAY-1500', draft: false }); }),
      http.get('*/api/v1/onboarding/PAY-1500', () => HttpResponse.json({ ...DRAFT, epic_key: 'PAY-1500', draft: false })),
      http.get('*/api/v1/onboarding/PAY-1500/tasks', () => HttpResponse.json({ anken_prefix: '', task_rule: '[{anken_prefix}][{os_task}] {sub_phase}', tasks: [] })),
      http.get('*/api/v1/onboarding/PAY-1500/members', () => HttpResponse.json(PEOPLE)),
    );
    const { router } = renderApp('/onboarding/draft-1/epic');
    const link = await screen.findByRole('region', { name: 'Link epic' });
    fireEvent.change(within(link).getByLabelText('Epic key'), { target: { value: 'pay-1500' } });
    fireEvent.click(within(link).getByRole('button', { name: 'Look up in Jira' }));
    fireEvent.click(await within(link).findByRole('button', { name: 'Link PAY-1500' }));
    await waitFor(() => expect(linked).toEqual({ key: 'PAY-1500' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/onboarding/PAY-1500/tasks'));
  });

  it('creates the missing template Tasks with their reviewed names, dates and labels', async () => {
    let created: Record<string, unknown> | null = null;
    const progress = onboarding({ step: 2, labels: ['PAY'] });
    server.use(
      http.get('*/api/v1/onboarding/PAY-1100/tasks', ({ request }) => {
        const prefix = new URL(request.url).searchParams.get('anken_prefix') ?? '';
        return HttpResponse.json({ anken_prefix: prefix, task_rule: '[{anken_prefix}][{os_task}] {sub_phase}', tasks: [
          { match: '[*][LocalTask] ITA Prepare', phase: 'ITA', existing_key: null, name: `[${prefix}][LocalTask] ITA Prepare`, recognised: true },
          { match: '[Prepare] QnA management', phase: 'Prepare', existing_key: 'PAY-1101', name: '[Prepare] QnA management', recognised: true },
        ] });
      }),
      http.post('*/api/v1/onboarding/PAY-1100/tasks/preview', () => HttpResponse.json({ updates: [], creates: [
        { ref: 'task:0', subtask: false, parent: 'PAY-1100', summary: '[PAY][LocalTask] ITA Prepare', request: { method: 'POST', path: '/rest/api/3/issue', body: '{}' } },
      ] })),
      http.post('*/api/v1/onboarding/PAY-1100/tasks', async ({ request }) => { created = await request.json() as typeof created; return HttpResponse.json({ updated: [], created: { 'task:0': 'PAY-1600' } }); }),
    );
    renderApp('/onboarding/PAY-1100/tasks');
    expect(await screen.findByDisplayValue('[PAY][LocalTask] ITA Prepare')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Setting Phase' })).toBeTruthy();
    expect(screen.getByText('Task pattern: [{anken_prefix}][{os_task}] {sub_phase}')).toBeTruthy();
    expect(screen.getByText('1 found · 1 missing')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
    fireEvent.change(screen.getByLabelText('Planned Start'), { target: { value: '2026-10-12' } });
    fireEvent.change(screen.getByLabelText('Planned End (Due date)'), { target: { value: '2026-10-30' } });
    expect((screen.getByLabelText('Labels') as HTMLInputElement).value).toBe('PAY'); // the saved ones
    fireEvent.change(screen.getByLabelText('Labels'), { target: { value: 'PAY, phase' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(progress).toContainEqual({ step: 2, anken_prefix: 'PAY', labels: ['PAY', 'phase'] }));
    fireEvent.click(screen.getByRole('button', { name: 'Review 1 Tasks' }));
    const review = await screen.findByRole('region', { name: 'Tasks to create' });
    expect(within(review).getByText('Planned 2026-10-12 → 2026-10-30 · Due date 2026-10-30 · Labels PAY, phase')).toBeTruthy();
    fireEvent.click(within(review).getByRole('button', { name: 'Create in Jira' }));
    await waitFor(() => expect(created).toEqual({
      anken_prefix: 'PAY', assignee_id: 'minh', planned_start: '2026-10-12', planned_end: '2026-10-30', labels: ['PAY', 'phase'],
      tasks: [{ match: '[*][LocalTask] ITA Prepare', name: '[PAY][LocalTask] ITA Prepare' }],
    }));
    expect(await screen.findByText('Created 1 Tasks in Jira')).toBeTruthy();
  });

  it('warns when the template would not recognise the Task pattern\'s name', async () => {
    onboarding({ step: 2 });
    server.use(http.get('*/api/v1/onboarding/PAY-1100/tasks', () => HttpResponse.json({ anken_prefix: 'PAY', task_rule: '{sub_phase} ({anken_prefix})', tasks: [
      { match: '[Prepare] QnA management', phase: 'Prepare', existing_key: null, name: 'QnA management (PAY)', recognised: false },
    ] })));
    renderApp('/onboarding/PAY-1100/tasks');
    expect(await screen.findByDisplayValue('QnA management (PAY)')).toBeTruthy();
    expect(screen.getByRole('alert').textContent).toContain('The template does not recognise this name');
  });

  it('saves the estimation with the Task of each row, then the progress', async () => {
    let saved: { rows: Record<string, unknown>[] } | null = null;
    const progress = onboarding();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/estimation', () => HttpResponse.json({ rows: [LOGIN], phases: [{ phase: 'ITA', total_effort_h: 14 }], tasks: [TASK, EXECUTE] })),
      http.put('*/api/v1/planning/PAY-1100/estimation', async ({ request }) => {
        saved = await request.json() as typeof saved;
        return HttpResponse.json({ rows: [LOGIN], phases: [{ phase: 'ITA', total_effort_h: 30 }], tasks: [TASK, EXECUTE] });
      }),
    );
    renderApp('/onboarding/PAY-1100/estimation');
    const totals = await screen.findByRole('region', { name: 'BA effort per phase' });
    expect(within(totals).getByText('14.00h')).toBeTruthy();
    expect(screen.getByLabelText('Task name, row 1').textContent).toBe('ITA Create test case');
    fireEvent.click(screen.getByRole('button', { name: 'Add row' }));
    expect(screen.getByLabelText('Task name, row 2').textContent).toBe('–');
    fireEvent.change(screen.getByLabelText('Task, row 2'), { target: { value: 'PAY-1103' } });
    expect(screen.getByLabelText('Task name, row 2').textContent).toBe('ITA Execute'); // the Task's Summary without its [...]
    fireEvent.change(screen.getByLabelText('Function, row 2'), { target: { value: 'Run' } });
    fireEvent.change(screen.getByLabelText('Steps, row 2'), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText('Effort (h), row 2'), { target: { value: '16' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(saved?.rows).toHaveLength(2));
    expect(saved!.rows[1]).toMatchObject({ id: 0, function_name: 'Run', steps: null, effort_h: 16, task_key: 'PAY-1103' });
    await waitFor(() => expect(progress).toContainEqual({ step: 3 }));
  });

  it('adds valid imported rows and lists the bad ones', async () => {
    let puts = 0;
    onboarding();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/estimation', () => HttpResponse.json({ rows: [], phases: [], tasks: [TASK] })),
      http.put('*/api/v1/planning/PAY-1100/estimation', () => { puts++; return HttpResponse.json({ rows: [], phases: [], tasks: [] }); }),
      http.post('*/api/v1/planning/PAY-1100/estimation/import', () => HttpResponse.json([
        { line: 2, task: 'PAY-1102', function: 'Login', steps: 5, effort_h: 8, errors: [] },
        { line: 3, task: 'PAY-1102', function: '', steps: 5, effort_h: 8, errors: ['Function is required'] },
      ])),
    );
    renderApp('/onboarding/PAY-1100/estimation');
    const file = await screen.findByLabelText('Import file');
    fireEvent.change(file, { target: { files: [new File(['x'], 'e.csv', { type: 'text/csv' })] } });
    expect(await screen.findByText('Line 3: Function is required')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Add 1 row' }));
    expect((screen.getByLabelText('Function, row 1') as HTMLInputElement).value).toBe('Login');
    expect((screen.getByLabelText('Task, row 1') as HTMLSelectElement).value).toBe('PAY-1102');
    expect(screen.getByLabelText('Task name, row 1').textContent).toBe('ITA Create test case');
    expect(puts).toBe(0);
  });

  it('saves the members that changed and continues', async () => {
    let saved: { members: unknown[] } | null = null;
    const progress = onboarding({ step: 4 });
    server.use(http.put('*/api/v1/onboarding/PAY-1100/members', async ({ request }) => { saved = await request.json() as typeof saved; return HttpResponse.json(PEOPLE); }));
    const { router } = renderApp('/onboarding/PAY-1100/members');
    fireEvent.click(await screen.findByLabelText('Include Tanaka'));
    fireEvent.change(screen.getByLabelText('Hours per day, Tanaka'), { target: { value: '1' } });
    expect(within(screen.getByRole('region', { name: 'Team totals' })).getByText('1.00h')).toBeTruthy(); // JM hours
    server.use(http.get('*/api/v1/planning/PAY-1100/master-schedule', () => HttpResponse.json({ estimation_phases: [], current: null, versions: [], shifts: [] })));
    fireEvent.click(screen.getByRole('button', { name: 'Continue to Master schedule' }));
    await waitFor(() => expect(saved).toEqual({ members: [{ member_id: 'tanaka', in_anken: true, hours_per_day: 1 }], allow_over_the_day: false }));
    await waitFor(() => expect(progress).toContainEqual({ step: 5 }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/onboarding/PAY-1100/schedule'));
  });

  it('adds a new member by name and role, keeping the other edits', async () => {
    let created: unknown = null;
    let people = PEOPLE;
    onboarding({ step: 4 });
    server.use(
      http.get('*/api/v1/onboarding/PAY-1100/members', () => HttpResponse.json(people)),
      http.post('*/api/v1/people', async ({ request }) => {
        created = await request.json();
        people = [...PEOPLE, { member_id: 'hoa', name: 'Hoa', role: 'JM', country: 'VN', in_anken: true, hours_per_day: null, elsewhere: [], working_day_h: 8 }];
        return HttpResponse.json({ id: 'hoa', name: 'Hoa' }, { status: 201 });
      }),
    );
    renderApp('/onboarding/PAY-1100/members');
    fireEvent.change(await screen.findByLabelText('Hours per day, An'), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: 'New member' }));
    const dialog = screen.getByRole('dialog', { name: 'New member' });
    expect((within(dialog).getByRole('button', { name: 'Add' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: ' Hoa ' } });
    fireEvent.change(within(dialog).getByLabelText('Role'), { target: { value: 'JM' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(created).toEqual({ name: 'Hoa', role: 'JM', email: null, epics: ['PAY-1100'] }));
    expect(await screen.findByLabelText('Include Hoa')).toBeTruthy();
    expect(screen.queryByRole('dialog', { name: 'New member' })).toBeNull();
    expect((screen.getByLabelText('Hours per day, An') as HTMLInputElement).value).toBe('3');
  });

  it('shows the hours already allocated on other anken on hover', async () => {
    onboarding({ step: 4 });
    renderApp('/onboarding/PAY-1100/members');
    fireEvent.mouseEnter(await screen.findByLabelText('Already allocated, Minh'));
    const pop = screen.getByRole('dialog', { name: 'Allocation · Minh' });
    expect(within(pop).getByText('PAY-1300').closest('div')?.textContent).toBe('PAY-1300 Refund4.00h');
  });

  it('warns about members over the working day and saves only once confirmed', async () => {
    let saved: { allow_over_the_day: boolean } | null = null;
    const progress = onboarding({ step: 4 });
    server.use(http.put('*/api/v1/onboarding/PAY-1100/members', async ({ request }) => { saved = await request.json() as typeof saved; return HttpResponse.json(PEOPLE); }));
    renderApp('/onboarding/PAY-1100/members');
    fireEvent.change(await screen.findByLabelText('Hours per day, Minh'), { target: { value: '6' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    const ask = await screen.findByRole('dialog', { name: 'Hours over the working day' });
    expect(within(ask).getByText('Minh')).toBeTruthy();
    expect(screen.getByText('10.00h a day with other anken, more than the 8.00h working day')).toBeTruthy();
    fireEvent.click(within(ask).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Hours over the working day' })).toBeNull());
    expect(saved).toBeNull();
    expect(progress).toEqual([]);
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Hours over the working day' })).getByRole('button', { name: 'Save anyway' }));
    await waitFor(() => expect(saved).toEqual({ members: [{ member_id: 'minh', in_anken: true, hours_per_day: 6 }], allow_over_the_day: true }));
  });

  it('chains the phases from one start date and saves the baseline', async () => {
    const previews: unknown[] = [];
    let saved: unknown = null;
    onboarding({ step: 5 });
    const rows = [
      { phase: 'ITA', total_effort_h: 30, member_ids: ['an'], start: '2026-10-12', end: '2026-10-15', plan_md: 4 },
      { phase: 'ITB', total_effort_h: 8, member_ids: ['an'], start: '2026-10-16', end: '2026-10-16', plan_md: 1 },
    ];
    server.use(
      http.get('*/api/v1/planning/PAY-1100/master-schedule', () => HttpResponse.json({ estimation_phases: ['ITA', 'ITB'], current: null, versions: [], shifts: [] })),
      http.post('*/api/v1/planning/PAY-1100/master-schedule/preview', async ({ request }) => { previews.push(await request.json()); return HttpResponse.json(rows); }),
      http.post('*/api/v1/planning/PAY-1100/master-schedule', async ({ request }) => { saved = await request.json(); return HttpResponse.json({ estimation_phases: ['ITA', 'ITB'], current: null, versions: [], shifts: [] }); }),
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json({ rows: [], tasks: [], window: [], lines: [], over_budget: [], allocated: [], over_day: [], checks: [], problems: [], push_blockers: [], days: [], stale_phases: [], by_hand: [], last_version_at: null })),
    );
    renderApp('/onboarding/PAY-1100/schedule');
    fireEvent.change(await screen.findByLabelText('Start date'), { target: { value: '2026-10-12' } });
    const body = { phases: [{ phase: 'ITA', start: '2026-10-12', member_ids: ['an'] }, { phase: 'ITB', start: null, member_ids: ['an'] }] };
    await waitFor(() => expect(previews).toContainEqual(body));
    const gantt = await screen.findByRole('region', { name: 'Gantt by phase' });
    expect(within(gantt).getByText('10/16 – 10/16')).toBeTruthy();
    // one column per day, Monday 12 to the Sunday after the last end, the weekend shaded
    const days = within(gantt).getByRole('row', { name: 'Days' });
    expect([...days.querySelectorAll('[title]')].map((d) => d.getAttribute('title'))).toEqual(
      ['Mon 10/12', 'Tue 10/13', 'Wed 10/14', 'Thu 10/15', 'Fri 10/16', 'Sat 10/17', 'Sun 10/18']);
    expect([...days.querySelectorAll('[data-weekend]')].map((d) => d.getAttribute('title'))).toEqual(['Sat 10/17', 'Sun 10/18']);
    fireEvent.click(screen.getByRole('button', { name: 'Continue to Detail plan' }));
    await waitFor(() => expect(saved).toEqual({ ...body, reason: 'Kick-off' }));
  });

  it('continues without a new version when the schedule is unchanged', async () => {
    let saves = 0;
    onboarding({ step: 6 });
    const phase = { phase: 'ITA', total_effort_h: 30, member_ids: ['an'], start: '2026-10-12', end: '2026-10-15', plan_md: 4 };
    const current = { id: 1, epic_key: 'PAY-1100', saved_at: '2026-10-07T09:00:00Z', saved_by: 'minh.hoang@acme.vn', reason: 'Kick-off', phases: [phase] };
    server.use(
      http.get('*/api/v1/planning/PAY-1100/master-schedule', () => HttpResponse.json({ estimation_phases: ['ITA'], current, versions: [current], shifts: [] })),
      http.post('*/api/v1/planning/PAY-1100/master-schedule/preview', async () => { await new Promise((r) => setTimeout(r, 500)); return HttpResponse.json([phase]); }),
      http.post('*/api/v1/planning/PAY-1100/master-schedule', () => { saves++; return HttpResponse.json({}); }),
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json({ rows: [], tasks: [], window: [], lines: [], over_budget: [], allocated: [], over_day: [], checks: [], problems: [], push_blockers: [], days: [], stale_phases: [], by_hand: [], last_version_at: null })),
    );
    const { router } = renderApp('/onboarding/PAY-1100/schedule');
    fireEvent.click(await screen.findByRole('button', { name: 'Continue to Detail plan' })); // before the preview is back
    await waitFor(() => expect(router.state.location.pathname).toBe('/onboarding/PAY-1100/detail'));
    expect(saves).toBe(0);
  });

  /** A line of the matrix: a step of a row not in Jira yet (row set) or a sub-task in Jira (key set). */
  const mline = (ref: string, row: number | null, step: string | null, summary: string, total: number, over: Record<string, unknown> = {}) => ({
    ref, row_id: row, issue_key: null as string | null, phase: 'ITA', function: row === 2 ? 'Run' : 'Login', step, summary, pic_id: null as string | null,
    reporter_id: null as string | null, total_h: total, spent_h: 0, planned_h: 0, not_yet_h: total, start: null as string | null, end: null as string | null,
    cells: [] as { day: string; hours: number; auto: boolean }[], logged: [] as { day: string; hours: number; auto: boolean }[], closed: false,
    pending: false, out_of_order: false, removed: false, edited: false, ...over,
  });
  const LINES = [mline('1:create', 1, 'create', '[PAY] Login – Create', 8), mline('1:balr', 1, 'balr', '[PAY] Login – BAL Review', 2),
    mline('2', 2, null, '[PAY] Run', 16)];
  // today is 2026-10-07 (meta): the 6th is past
  const WINDOW = ['2026-10-06', '2026-10-07', '2026-10-08', '2026-10-09', '2026-10-12', '2026-10-13', '2026-10-14'];
  const REFUND = mline('PAY-1201', null, 'create', '[PAY] Refund – Create', 3, { issue_key: 'PAY-1201', function: 'Refund', pic_id: 'an',
    spent_h: 1, planned_h: 3, not_yet_h: 0, start: '2026-10-06', end: '2026-10-08',
    cells: [{ day: '2026-10-06', hours: 1, auto: false }, { day: '2026-10-08', hours: 2, auto: false }], logged: [{ day: '2026-10-06', hours: 1.5, auto: false }] });
  const detailView = (over: Record<string, unknown> = {}) => ({
    rows: [LOGIN, RUN], tasks: [TASK], window: WINDOW, lines: LINES, over_budget: [], allocated: [], over_day: [], checks: [], days: [],
    stale_phases: [], problems: [], push_blockers: [], by_hand: [], last_version_at: null, ...over,
  });
  const run = (over: Record<string, unknown>) => mline('2', 2, null, '[PAY] Run', 16, over);
  const planned = {
    lines: [...LINES.slice(0, 2), run({ planned_h: 16, not_yet_h: 0, start: '2026-10-12', end: '2026-10-14', pic_id: 'an',
      cells: [{ day: '2026-10-12', hours: 8, auto: true }, { day: '2026-10-14', hours: 8, auto: true }] })],
    checks: [{ phase: 'ITA', master_end: '2026-10-13', plan_end: '2026-10-14', days_late: 1 }],
    days: [{ member_id: 'an', date: '2026-10-11', planned_h: 0, allocated_h: 0, off: 'Weekend', other_h: 0, items: [] },
      { member_id: 'an', date: '2026-10-12', planned_h: 8, allocated_h: 8, off: null, other_h: 0, items: [{ key: null, title: '[PAY] Run', hours: 8, other: false }] },
      { member_id: 'an', date: '2026-10-13', planned_h: 9, allocated_h: 8, off: null, other_h: 3, items: [{ key: null, title: '[PAY] Run', hours: 8, other: false },
        { key: 'PAY-1290', title: 'Refund – Create', hours: 1, other: false }, { key: 'PAY-1300', title: 'Refund', hours: 3, other: true }] }],
  };

  it('highlights the people picked by hand and clears the plan with or without them', async () => {
    const clears: string[] = [];
    onboarding({ step: 6 });
    const view = detailView({ rows: [{ ...LOGIN, ba_id: 'an', bal_id: 'minh', jm_id: 'tanaka' }, RUN], ...planned,
      by_hand: [{ row_id: 1, ba: true, bal: false, jm: false }] });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(view)),
      http.post('*/api/v1/planning/PAY-1100/detail/clear', ({ request }) => {
        clears.push(new URL(request.url).searchParams.get('keep_input') ?? '');
        return HttpResponse.json(view);
      }),
    );
    renderApp('/onboarding/PAY-1100/detail');
    const ba = await screen.findByLabelText('BA, Login');
    expect(ba.getAttribute('data-by-hand')).toBe('true');
    expect(screen.getByLabelText('BAL, Login').getAttribute('data-by-hand')).toBeNull(); // Auto allocate's pick
    fireEvent.click(screen.getByRole('button', { name: 'More' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Clear, keep my input' }));
    await waitFor(() => expect(clears).toEqual(['true']));
    expect(screen.queryByRole('menu')).toBeNull(); // a pick closes the menu
    fireEvent.click(screen.getByRole('button', { name: 'More' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Clear all' }));
    const dialog = await screen.findByRole('dialog', { name: 'Clear the plan' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Clear all' }));
    await waitFor(() => expect(clears).toEqual(['true', 'false']));
    expect(await screen.findByText('Plan cleared')).toBeTruthy();
  });

  it('assigns members and shows the plan with the effort per member per day above it', async () => {
    let saved: { assigns: Record<string, unknown>[] } | null = null;
    onboarding({ step: 6 });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ problems: ['Row 1 (Login): pick a BA'] }))),
      http.put('*/api/v1/planning/PAY-1100/detail', async ({ request }) => {
        saved = await request.json() as typeof saved;
        return HttpResponse.json(detailView({ rows: [{ ...LOGIN, ba_id: 'an' }, RUN], ...planned }));
      }),
    );
    renderApp('/onboarding/PAY-1100/detail');
    const ba = await screen.findByLabelText('BA, Login');
    expect(screen.getAllByLabelText('BA, Login')).toHaveLength(1); // picked once for the function, not on each of its lines
    expect(screen.queryByText('Row 1 (Login): pick a BA')).toBeNull(); // not on the first visit, only counted
    expect(screen.getByRole('button', { name: '1 problem before the push' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Fix 1 problem to push' })).toBeTruthy();
    expect([...ba.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['–', 'An (BA)', 'Minh (BAL)']);
    expect([...screen.getByLabelText('JM, Login').querySelectorAll('option')].map((o) => o.textContent)).toEqual(['–']); // no BA or BAL
    expect(screen.getByLabelText('Member, Run')).toBeTruthy(); // a ticket has one member
    expect(screen.queryByRole('button', { name: 'Check' })).toBeNull();
    expect(screen.queryByRole('tab')).toBeNull(); // the effort per day is always above the matrix
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(await screen.findByText('Row 1 (Login): pick a BA')).toBeTruthy();
    expect(screen.getByRole('row', { name: '[PAY] Login – BAL Review' })).toBeTruthy(); // one line per step, named by the name rules
    fireEvent.change(screen.getByLabelText('BA, Login'), { target: { value: 'an' } });
    await waitFor(() => expect(saved).toEqual({ order: [1, 2], assigns: [{ row_id: 1, ba_id: 'an', bal_id: null, jm_id: null }, { row_id: 2, ba_id: 'an', bal_id: null, jm_id: null }] }));
    const runRow = await screen.findByRole('row', { name: '[PAY] Run' });
    await waitFor(() => expect(within(runRow).getByLabelText('Start, [PAY] Run').textContent).toBe('10/12'));
    expect(within(runRow).getByLabelText('End, [PAY] Run').textContent).toBe('10/14');
    expect(within(runRow).getByLabelText('Planned, [PAY] Run').textContent).toBe('16.00');
    expect((within(runRow).getByLabelText('[PAY] Run 2026-10-14') as HTMLInputElement).value).toBe('8');
    expect(within(runRow).getByLabelText('PIC, [PAY] Run').textContent).toBe('An'); // who carries the line
    expect(screen.getByText('ITA ends 10/14 · master 10/13 · 1 day late')).toBeTruthy();
    const days = screen.getByRole('region', { name: 'Effort per member per day' });
    const tue = within(days).getByLabelText('An 2026-10-13');
    expect(tue.textContent).toBe('9.00+3.00 other'); // the planned hours only, red above the 8h allocation
    expect(within(days).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Member', 'Mon', 'Tue', '10/12', '10/13']); // no weekend
    fireEvent.mouseEnter(tue);
    const tip = screen.getByRole('tooltip');
    expect(days.contains(tip)).toBe(false); // above the page, not clipped by the section
    expect(tip.textContent).toContain('PAY-1290 · Refund – Create'); // a task of this anken
    expect(tip.textContent).toContain('Other anken');
    expect(tip.textContent).toContain('Refund3.00h'); // another anken: its name and hours
    expect(screen.getByRole('button', { name: 'Finish onboarding' }).getAttribute('title')).toBe('Push every row to Jira first');
  });

  it('types hours into a day and sends them when the cell is left', async () => {
    const sent: unknown[] = [];
    onboarding({ step: 6 });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.put('*/api/v1/planning/PAY-1100/detail/cells', async ({ request }) => {
        sent.push(await request.json());
        return HttpResponse.json(detailView({ lines: [...LINES.slice(0, 2),
          run({ planned_h: 8, not_yet_h: 8, start: '2026-10-12', end: '2026-10-12', cells: [{ day: '2026-10-12', hours: 8, auto: false }] })] }));
      }),
    );
    renderApp('/onboarding/PAY-1100/detail');
    const cell = await screen.findByLabelText('[PAY] Run 2026-10-12') as HTMLInputElement;
    expect(within(screen.getByRole('row', { name: '[PAY] Run' })).getByLabelText('Not yet, [PAY] Run').textContent).toBe('16.00');
    fireEvent.change(cell, { target: { value: '8' } });
    expect(sent).toEqual([]); // not while it is typed
    expect(cell.value).toBe('8');
    fireEvent.blur(cell);
    await waitFor(() => expect(sent).toEqual([{ cells: [{ ref: '2', day: '2026-10-12', hours: 8 }] }]));
    const row = screen.getByRole('row', { name: '[PAY] Run' });
    await waitFor(() => expect(within(row).getByLabelText('Not yet, [PAY] Run').textContent).toBe('8.00'));
    expect(within(row).getByLabelText('Start, [PAY] Run').textContent).toBe('10/12');
    expect((screen.getByLabelText('[PAY] Run 2026-10-12') as HTMLInputElement).value).toBe('8');
    const next = screen.getByLabelText('[PAY] Run 2026-10-13');
    fireEvent.change(next, { target: { value: '' } });
    fireEvent.keyDown(next, { key: 'Enter' });
    fireEvent.blur(next); // nothing changed: nothing sent
    expect(sent).toHaveLength(1);
  });

  it('locks the past days of a Jira line and shows the hours logged', async () => {
    onboarding({ step: 6 });
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ lines: [...LINES, { ...REFUND, pending: true }] }))));
    renderApp('/onboarding/PAY-1100/detail');
    const row = await screen.findByRole('row', { name: '[PAY] Refund – Create' });
    expect(screen.queryByLabelText('[PAY] Refund – Create 2026-10-06')).toBeNull();
    const past = row.querySelector('[data-day="2026-10-06"]') as HTMLElement;
    expect(within(past).getByText('1')).toBeTruthy(); // the plan
    expect(within(past).getByText('1.5h')).toBeTruthy(); // the hours logged
    expect((screen.getByLabelText('[PAY] Refund – Create 2026-10-07') as HTMLInputElement).disabled).toBe(false); // today stays open
    expect((screen.getByLabelText('[PAY] Refund – Create 2026-10-08') as HTMLInputElement).value).toBe('2');
    expect(within(row).getByText('Not pushed')).toBeTruthy();
    expect(within(row).getByText('PAY-1201')).toBeTruthy();
  });

  it('names a PIC outside the anken and marks only a real Not yet of an open line', async () => {
    onboarding({ step: 6 });
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ lines: [LINES[0], LINES[1], run({ not_yet_h: 0.01 }),
      { ...REFUND, pic_id: 'binh', closed: true, not_yet_h: -2 },
      { ...REFUND, ref: 'PAY-1202', issue_key: 'PAY-1202', summary: '[PAY] Refund – BAL Review', step: 'balr', pic_id: 'dung' }] }))));
    renderApp('/onboarding/PAY-1100/detail');
    const closed = await screen.findByRole('row', { name: '[PAY] Refund – Create' });
    expect(within(closed).getByLabelText('PIC, [PAY] Refund – Create').textContent).toBe('Bình'); // by name, though not in the anken
    const pic = screen.getByLabelText('PIC, [PAY] Refund – BAL Review') as HTMLSelectElement;
    expect([...pic.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['An', 'Minh', 'Dũng']); // the anken's members, and who has it
    const bold = (summary: string) => (screen.getByLabelText(`Not yet, ${summary}`) as HTMLElement).style.fontWeight;
    expect(bold('[PAY] Login – Create')).toBe('700'); // 8h left: red
    expect(bold('[PAY] Run')).toBe(''); // a hundredth is planned enough, as the push sees it
    expect(bold('[PAY] Refund – Create')).toBe(''); // Closed: done
  });

  it('changes the Total and the PIC of a Jira line', async () => {
    const sent: unknown[] = [];
    onboarding({ step: 6 });
    const view = detailView({ lines: [...LINES, REFUND] });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(view)),
      http.put('*/api/v1/planning/PAY-1100/detail/jira/PAY-1201', async ({ request }) => {
        sent.push(await request.json());
        return HttpResponse.json(view);
      }),
    );
    renderApp('/onboarding/PAY-1100/detail');
    const total = await screen.findByLabelText('Total, [PAY] Refund – Create') as HTMLInputElement;
    expect(total.value).toBe('3');
    fireEvent.change(total, { target: { value: '5' } });
    expect(sent).toEqual([]);
    fireEvent.blur(total);
    await waitFor(() => expect(sent).toEqual([{ total_h: 5, pic_id: null }]));
    const pic = screen.getByLabelText('PIC, [PAY] Refund – Create') as HTMLSelectElement;
    expect(pic.value).toBe('an');
    expect([...pic.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['An', 'Minh']); // the anken's members
    fireEvent.change(pic, { target: { value: 'minh' } });
    await waitFor(() => expect(sent).toEqual([{ total_h: 5, pic_id: null }, { total_h: null, pic_id: 'minh' }]));
  });

  it('marks a step out of order and blocks the push', async () => {
    onboarding({ step: 6 });
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({
      lines: [LINES[0], { ...LINES[1], out_of_order: true, cells: [{ day: '2026-10-08', hours: 2, auto: false }] }, LINES[2]],
      push_blockers: ['[PAY] Login – BAL Review: starts before the step before it ends'],
    }))));
    renderApp('/onboarding/PAY-1100/detail');
    const row = await screen.findByRole('row', { name: '[PAY] Login – BAL Review' });
    expect(row.getAttribute('data-out-of-order')).toBe('true');
    const cell = within(row).getByLabelText('[PAY] Login – BAL Review 2026-10-08').closest('td');
    expect(cell?.getAttribute('data-out-of-order')).toBe('true');
    expect(screen.getByRole('row', { name: '[PAY] Run' }).getAttribute('data-out-of-order')).toBeNull();
    expect((screen.getByRole('button', { name: 'Review push' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Fix 1 problem to push' }));
    expect(await screen.findByText('[PAY] Login – BAL Review: starts before the step before it ends')).toBeTruthy();
  });

  it('sends a write after the cell saved before it, not alongside it', async () => {
    const order: string[] = [];
    let release = () => {};
    onboarding({ step: 6 });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.put('*/api/v1/planning/PAY-1100/detail/cells', async () => {
        order.push('cells');
        await new Promise<void>((r) => { release = r; });
        order.push('cells done');
        return HttpResponse.json(detailView());
      }),
      http.post('*/api/v1/planning/PAY-1100/detail/versions', () => { order.push('version'); return HttpResponse.json(detailView()); }),
    );
    renderApp('/onboarding/PAY-1100/detail');
    const cell = await screen.findByLabelText('[PAY] Run 2026-10-12');
    fireEvent.change(cell, { target: { value: '8' } });
    fireEvent.blur(cell);
    await waitFor(() => expect(order).toEqual(['cells']));
    fireEvent.click(screen.getByRole('button', { name: 'Save version' }));
    await new Promise((r) => setTimeout(r, 100));
    expect(order).toEqual(['cells']); // the version waits for the cell
    release();
    await waitFor(() => expect(order).toEqual(['cells', 'cells done', 'version']));
  });

  it('keeps hours typed again while the earlier ones are being saved', async () => {
    const sent: unknown[] = [];
    let release = () => {};
    onboarding({ step: 6 });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.put('*/api/v1/planning/PAY-1100/detail/cells', async ({ request }) => {
        const b = await request.json() as { cells: { hours: number }[] };
        sent.push(b);
        if (sent.length === 1) await new Promise<void>((r) => { release = r; });
        return HttpResponse.json(detailView({ lines: [...LINES.slice(0, 2), run({ cells: [{ day: '2026-10-12', hours: b.cells[0].hours, auto: false }] })] }));
      }),
    );
    renderApp('/onboarding/PAY-1100/detail');
    const cell = await screen.findByLabelText('[PAY] Run 2026-10-12') as HTMLInputElement;
    fireEvent.change(cell, { target: { value: '8' } });
    fireEvent.blur(cell);
    await waitFor(() => expect(sent).toHaveLength(1));
    fireEvent.change(cell, { target: { value: '6' } }); // typed again before the 8 is saved
    release();
    await waitFor(() => expect((screen.getByLabelText('[PAY] Run 2026-10-12') as HTMLInputElement).value).toBe('6'));
    await new Promise((r) => setTimeout(r, 50));
    expect(cell.value).toBe('6'); // the answer to the 8 does not wipe the 6
    fireEvent.blur(cell);
    await waitFor(() => expect(sent).toEqual([{ cells: [{ ref: '2', day: '2026-10-12', hours: 8 }] }, { cells: [{ ref: '2', day: '2026-10-12', hours: 6 }] }]));
  });

  it('can clear the plan when every line is removed', async () => {
    onboarding({ step: 6 });
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ lines: LINES.map((l) => ({ ...l, removed: true })) }))));
    renderApp('/onboarding/PAY-1100/detail');
    fireEvent.click(await screen.findByRole('button', { name: 'More' }));
    expect(screen.getByRole('menuitem', { name: 'Clear all' })).toBeTruthy(); // brings the removed lines back
  });

  it('saves a version', async () => {
    let posts = 0;
    onboarding({ step: 6 });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.post('*/api/v1/planning/PAY-1100/detail/versions', () => {
        posts++;
        return HttpResponse.json(detailView({ last_version_at: '2026-10-07T09:30:00Z' }));
      }),
    );
    renderApp('/onboarding/PAY-1100/detail');
    fireEvent.click(await screen.findByRole('button', { name: 'Save version' }));
    expect(await screen.findByText('Version saved')).toBeTruthy();
    expect(posts).toBe(1);
    expect(screen.getByText(/^Last version 2026\/10\/0[78] \d\d:\d\d$/)).toBeTruthy();
  });

  it('is ready to push lines not planned yet', async () => {
    onboarding({ step: 6 });
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())));
    renderApp('/onboarding/PAY-1100/detail');
    expect(await screen.findByText('Ready to push')).toBeTruthy(); // nothing stands before the push: created without dates
    expect((screen.getByRole('button', { name: 'Review push' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('auto allocates the empty slots and shows the plan that follows', async () => {
    let posts = 0;
    onboarding({ step: 6 });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.post('*/api/v1/planning/PAY-1100/detail/auto-allocate', () => {
        posts++;
        return HttpResponse.json(detailView({ rows: [{ ...LOGIN, ba_id: 'an' }, { ...RUN, ba_id: 'an' }], ...planned }));
      }),
    );
    renderApp('/onboarding/PAY-1100/detail');
    fireEvent.click(await screen.findByRole('button', { name: 'Auto allocate' }));
    await waitFor(() => expect((screen.getByLabelText('BA, Login') as HTMLSelectElement).value).toBe('an'));
    expect((screen.getByLabelText('[PAY] Run 2026-10-12') as HTMLInputElement).value).toBe('8');
    expect(posts).toBe(1);
  });

  it('edits, removes and restores a line, and adds a function to the estimation', async () => {
    const steps: Record<string, unknown>[] = [];
    let added: Record<string, unknown> | null = null;
    onboarding({ step: 6 });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.put('*/api/v1/planning/PAY-1100/detail/step', async ({ request }) => {
        const b = await request.json() as Record<string, unknown>;
        steps.push(b);
        return HttpResponse.json(detailView({ lines: LINES.map((l) => (l.ref === '1:balr' ? { ...l, removed: !!b.removed } : l)) }));
      }),
      http.post('*/api/v1/planning/PAY-1100/detail/rows', async ({ request }) => {
        added = await request.json() as Record<string, unknown>;
        return HttpResponse.json(detailView());
      }),
    );
    renderApp('/onboarding/PAY-1100/detail');
    const effort = await screen.findByLabelText('Total, [PAY] Login – BAL Review');
    fireEvent.change(effort, { target: { value: '3' } });
    fireEvent.blur(effort);
    await waitFor(() => expect(steps[0]).toEqual({ row_id: 1, step: 'balr', effort_h: 3, removed: false }));
    fireEvent.click(screen.getByRole('button', { name: 'Remove [PAY] Login – BAL Review' }));
    const restore = await screen.findByRole('button', { name: 'Restore [PAY] Login – BAL Review' });
    // greyed, its days not editable
    expect(screen.getByRole('row', { name: '[PAY] Login – BAL Review' }).getAttribute('data-removed')).toBe('true');
    expect(screen.queryByLabelText('[PAY] Login – BAL Review 2026-10-12')).toBeNull();
    fireEvent.click(restore);
    await waitFor(() => expect(steps.map((b) => b.removed)).toEqual([false, true, false]));

    fireEvent.click(screen.getByRole('button', { name: 'Add function' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add function' });
    fireEvent.change(within(dialog).getByLabelText('Function'), { target: { value: 'Export' } });
    fireEvent.change(within(dialog).getByLabelText('Create effort (h)'), { target: { value: '6' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(added).toEqual({ task_key: 'PAY-1102', function_name: 'Export', steps: 5, effort_h: 6 }));
  });

  it('imports a filled template: shows what changes and what is wrong, then applies the good lines', async () => {
    let applied: { lines: Record<string, unknown>[] } | null = null;
    onboarding({ step: 6 });
    const imported = [
      { line: 2, ref: '1:create', function: 'Login', step: 'create', ba_id: 'minh', bal_id: null, jm_id: null, effort_h: 10, cells: {},
        changes: [{ field: 'ba', before: 'an', after: 'minh' }, { field: 'effort_h', before: '8', after: '10' }], errors: [] },
      { line: 3, ref: '1:balr', function: 'Login', step: 'balr', ba_id: null, bal_id: null, jm_id: null, effort_h: 2, cells: {},
        changes: [], errors: [] },
      { line: 4, ref: '2', function: 'Run', step: null, ba_id: null, bal_id: null, jm_id: null, effort_h: 16, cells: { '2026-10-12': 0, '2026-10-13': 8 },
        changes: [{ field: 'cells', before: '2026-10-12 8, 2026-10-13 0', after: '2026-10-12 0, 2026-10-13 8' }], errors: [] },
      { line: 5, ref: '9', function: 'Ghost', step: null, ba_id: null, bal_id: null, jm_id: null, effort_h: null, cells: {},
        changes: [], errors: ['9 is not a sub-task of the plan'] },
    ];
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ rows: [{ ...LOGIN, ba_id: 'an' }, RUN], ...planned }))),
      http.post('*/api/v1/planning/PAY-1100/detail/import/preview', () => HttpResponse.json(imported)),
      http.post('*/api/v1/planning/PAY-1100/detail/import', async ({ request }) => {
        applied = await request.json() as typeof applied;
        return HttpResponse.json(detailView({ rows: [{ ...LOGIN, ba_id: 'minh' }, RUN], ...planned }));
      }),
    );
    renderApp('/onboarding/PAY-1100/detail');
    await screen.findByLabelText('BA, Login');
    fireEvent.click(screen.getByRole('button', { name: 'More' }));
    expect(screen.getByRole('menuitem', { name: 'Download template' })).toBeTruthy();
    fireEvent.click(screen.getByRole('menuitem', { name: 'Import Excel / CSV' }));
    fireEvent.change(screen.getByLabelText('Import file'), { target: { files: [new File(['x'], 'plan.xlsx')] } });
    const dialog = await screen.findByRole('dialog', { name: 'Import the detail plan' });
    expect(within(dialog).getByRole('status').textContent).toBe('plan.xlsx: 2 lines to change · 1 unchanged · 1 with errors, left out');
    expect(within(dialog).getByText('Line 5 · Ghost: 9 is not a sub-task of the plan')).toBeTruthy();
    expect(within(dialog).getByText('Line 2 · Login · Create')).toBeTruthy();
    expect(within(dialog).getByText('BA An → Minh')).toBeTruthy();
    expect(within(dialog).getByText('Effort 8.00h → 10.00h')).toBeTruthy();
    expect(within(dialog).getByText('Hours 10/12 8.00h → 0.00h · 10/13 0.00h → 8.00h')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Apply 2 lines' }));
    await waitFor(() => expect(applied?.lines.map((l) => l.ref)).toEqual(['1:create', '2'])); // the unchanged and wrong lines stay out
    expect(applied!.lines[0]).toEqual({ ref: '1:create', ba_id: 'minh', bal_id: null, jm_id: null, effort_h: 10, cells: {} });
    expect(applied!.lines[1].cells).toEqual({ '2026-10-12': 0, '2026-10-13': 8 });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect((screen.getByLabelText('BA, Login') as HTMLSelectElement).value).toBe('minh');
  });

  it('warns about days above the allocation and pushes anyway', async () => {
    let allowed: string | null = 'not sent';
    onboarding({ step: 6 });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({
        ...planned, over_day: [{ member_id: 'an', date: '2026-10-13', planned_h: 9, allocation_h: 8 }],
      }))),
      http.post('*/api/v1/planning/PAY-1100/detail/push/preview', ({ request }) => {
        allowed = new URL(request.url).searchParams.get('allow_over_day');
        return HttpResponse.json({ updates: [], creates: [] });
      }),
    );
    renderApp('/onboarding/PAY-1100/detail');
    expect(await screen.findByRole('button', { name: '1 day above the allocation' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Review push' }));
    const dialog = await screen.findByRole('dialog', { name: 'Push review' }); // the warnings show again in the review
    expect(allowed).toBeNull(); // nothing to confirm
    expect(within(dialog).getByRole('note', { name: 'Days above the allocation' }).textContent).toContain('An has 9.00h planned on 10/13, above the 8.00h allocation');
    const warn = screen.getByRole('region', { name: 'Over the allocation' });
    expect(warn.textContent).toContain('An has 9.00h planned on 10/13, above the 8.00h allocation');
    expect(screen.queryByRole('alert', { name: 'Before the push' })).toBeNull(); // a warning, not a block
  });

  it('warns while a member is over budget, without blocking the push', async () => {
    onboarding({ step: 6 });
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({
      ...planned, over_budget: [{ member_id: 'an', phase: 'ITA', planned_h: 40, budget_h: 36 }],
      allocated: [{ member_id: 'an', phase: 'ITA', date: '2026-10-12', ankens: [
        { epic_key: 'PAY-1100', title: 'Payment', hours_per_day: 3 }, { epic_key: 'CRM-400', title: 'CRM', hours_per_day: 5 }] }],
    }))));
    renderApp('/onboarding/PAY-1100/detail');
    fireEvent.click(await screen.findByRole('button', { name: 'Finish onboarding' })); // not every row is in Jira: shows what stands before
    expect(await screen.findByText('An is over budget in ITA: 40.00h of 36.00h')).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Over the allocation' }).textContent).toContain('An is over budget in ITA');
    expect(screen.queryByRole('alert', { name: 'Before the push' })).toBeNull();
    const matrix = screen.getByRole('table', { name: 'Allocated per anken' });
    expect(matrix.textContent).toContain('PAY-1100 (this)');
    expect(screen.getByRole('row', { name: /An · ITA \(from 10\/12\)/ }).textContent).toBe('An · ITA (from 10/12)3.00h5.00h8.00h');
    expect((screen.getByRole('button', { name: 'Review push' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('pushes the hours per day alone when Jira has nothing to change', async () => {
    let pushes = 0;
    onboarding({ step: 6 });
    const view = detailView({ rows: [{ ...LOGIN, pushed_at: '2026-10-07T09:00:00Z' }, { ...RUN, pushed_at: '2026-10-07T09:00:00Z' }],
      lines: [{ ...REFUND, pending: true }] });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(view)),
      http.post('*/api/v1/planning/PAY-1100/detail/push/preview', () => HttpResponse.json({ updates: [], creates: [] })),
      http.post('*/api/v1/planning/PAY-1100/detail/push', () => { pushes++; return HttpResponse.json({ updated: [], created: {} }); }),
    );
    renderApp('/onboarding/PAY-1100/detail');
    fireEvent.click(await screen.findByRole('button', { name: 'Review push' })); // a pending Jira line is something to push
    const review = await screen.findByRole('dialog', { name: 'Push review' });
    expect(within(review).getByText('Nothing changes in Jira')).toBeTruthy();
    fireEvent.click(within(review).getByRole('button', { name: 'Push to Jira' }));
    await waitFor(() => expect(pushes).toBe(1));
    expect(await screen.findByText('Pushed · the hours per day are kept here')).toBeTruthy();
  });

  it('pushes the plan, then finishes the onboarding', async () => {
    let finished = false;
    let pushedRows = false;
    onboarding({ step: 6, labels: ['PAY', 'phase'] });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(pushedRows
        ? detailView({ rows: [{ ...LOGIN, pushed_at: '2026-10-07T09:00:00Z' }, { ...RUN, pushed_at: '2026-10-07T09:00:00Z' }], lines: [REFUND] })
        : detailView({ ...planned, lines: [...planned.lines, { ...REFUND, pending: true }] }))),
      http.post('*/api/v1/planning/PAY-1100/detail/push/preview', () => HttpResponse.json({ updates: [
        { key: 'PAY-1201', summary: '[PAY] Refund – Create', transition: null, jira_status: null, estimate_tracking: false, requests: [],
          changes: [{ field: 'remaining', jira_field: 'timeestimate', before: '2', after: '4' }, { field: 'assignee', jira_field: 'assignee', before: 'an', after: 'minh' }] },
      ], creates: [
        { ref: '2', subtask: true, parent: 'PAY-1102', summary: '[ITA] Run', request: { method: 'POST', path: '/rest/api/3/issue', body: '{}' } },
      ] })),
      http.post('*/api/v1/planning/PAY-1100/detail/push', () => { pushedRows = true; return HttpResponse.json({ updated: ['PAY-1201'], created: { 2: 'PAY-1502' } }); }),
      http.post('*/api/v1/onboarding/PAY-1100/finish', () => { finished = true; return HttpResponse.json({ ...ONB, step: 6, finished_at: '2026-10-07T10:00:00Z' }); }),
      http.get('*/api/v1/onboarding', () => HttpResponse.json([])),
    );
    const { router } = renderApp('/onboarding/PAY-1100/detail');
    fireEvent.click(await screen.findByRole('button', { name: 'Review push' }));
    const review = await screen.findByRole('dialog', { name: 'Push review' });
    expect(within(review).getByText('[ITA] Run')).toBeTruthy();
    expect(within(review).getByText('Labels PAY, phase (Setting Phase)')).toBeTruthy();
    const update = within(review).getByRole('listitem', { name: 'PAY-1201' });
    expect(update.textContent).toContain('[PAY] Refund – Create');
    expect(within(update).getByText('Remaining 2.00h → 4.00h')).toBeTruthy();
    expect(within(update).getByText('PIC An → Minh')).toBeTruthy();
    fireEvent.click(within(review).getByRole('button', { name: 'Push to Jira' }));
    expect(await screen.findByText('Created 1 sub-tasks · updated 1 in Jira')).toBeTruthy();
    const finish = screen.getByRole('button', { name: 'Finish onboarding' }) as HTMLButtonElement;
    await waitFor(() => expect(finish.disabled).toBe(false));
    fireEvent.click(finish);
    await waitFor(() => expect(finished).toBe(true));
    await waitFor(() => expect(router.state.location.pathname).toBe('/anken/PAY-1100'));
  });
});
