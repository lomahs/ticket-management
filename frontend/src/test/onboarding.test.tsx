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
const STRUCTURE = { phases: ['ITA'], daily: [], seen: [], tasks: [{ match: '[*][LocalTask] ITA Prepare', phase: 'ITA', prefix: 'P', workflow: true, type: '' }] };
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
    expect(within(band).getAllByRole('listitem').map((s) => s.textContent?.startsWith('✓'))).toEqual([true, false, false, false, false]);
  });

  it('lists the ankens in progress and resumes one at its step', async () => {
    let deleted = false;
    server.use(
      http.get('*/api/v1/onboarding', () => HttpResponse.json([ONB, { ...ONB, epic_key: 'CRM-450', title: 'Campaigns', step: 5, finished_at: '2026-10-06T09:00:00Z' }])),
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
      http.get('*/api/v1/onboarding/draft-1/structure', () => HttpResponse.json(STRUCTURE)),
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
      http.get('*/api/v1/onboarding/draft-1/structure', () => HttpResponse.json(STRUCTURE)),
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

  it('sets the Phases & tasks of an anken without its epic in Setting Phase', async () => {
    let saved: unknown = null;
    server.use(
      http.get('*/api/v1/onboarding/draft-1', () => HttpResponse.json({ ...DRAFT, anken_prefix: 'RFD' })),
      http.get('*/api/v1/onboarding/draft-1/members', () => HttpResponse.json(PEOPLE)),
      http.get('*/api/v1/onboarding/draft-1/structure', () => HttpResponse.json(STRUCTURE)),
      http.put('*/api/v1/onboarding/draft-1/structure', async ({ request }) => { saved = await request.json(); return HttpResponse.json({ ...STRUCTURE, ...(saved as object) }); }),
      http.get('*/api/v1/onboarding/draft-1/tasks', () => HttpResponse.json({ anken_prefix: 'RFD', task_rule: '[{anken_prefix}][{os_task}] {sub_phase}', tasks: [] })),
    );
    renderApp('/onboarding/draft-1/tasks');
    const tasks = await screen.findByRole('region', { name: 'Tasks' });
    expect((within(tasks).getByLabelText('Task 1 name') as HTMLInputElement).value).toBe('[*][LocalTask] ITA Prepare');
    fireEvent.click(within(tasks).getByRole('button', { name: 'Add task' }));
    fireEvent.change(within(tasks).getByLabelText('Task 2 name'), { target: { value: '[*][LocalTask] ITA Execute' } });
    fireEvent.change(within(tasks).getByLabelText('Task 2 phase'), { target: { value: 'ITA' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saved).toEqual({ phases: ['ITA'], daily: [], tasks: [
      STRUCTURE.tasks[0], { match: '[*][LocalTask] ITA Execute', phase: 'ITA', prefix: '', workflow: true, type: '' },
    ] }));
  });

  it('keeps the Phases & tasks of an anken with its epic in Settings', async () => {
    onboarding({ step: 2 });
    server.use(http.get('*/api/v1/onboarding/PAY-1100/tasks', () => HttpResponse.json({ anken_prefix: 'PAY', task_rule: '', tasks: [] })));
    renderApp('/onboarding/PAY-1100/tasks');
    expect(await screen.findByText(/template has no Tasks/)).toBeTruthy();
    expect(screen.queryByRole('region', { name: 'Phases' })).toBeNull();
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
    let finished = false;
    onboarding({ step: 5 });
    const rows = [
      { phase: 'ITA', total_effort_h: 30, member_ids: ['an'], start: '2026-10-12', end: '2026-10-15', plan_md: 4 },
      { phase: 'ITB', total_effort_h: 8, member_ids: ['an'], start: '2026-10-16', end: '2026-10-16', plan_md: 1 },
    ];
    server.use(
      http.get('*/api/v1/planning/PAY-1100/master-schedule', () => HttpResponse.json({ estimation_phases: ['ITA', 'ITB'], current: null, versions: [], shifts: [] })),
      http.post('*/api/v1/planning/PAY-1100/master-schedule/preview', async ({ request }) => { previews.push(await request.json()); return HttpResponse.json(rows); }),
      http.post('*/api/v1/planning/PAY-1100/master-schedule', async ({ request }) => { saved = await request.json(); return HttpResponse.json({ estimation_phases: ['ITA', 'ITB'], current: null, versions: [], shifts: [] }); }),
      http.post('*/api/v1/onboarding/PAY-1100/finish', () => { finished = saved != null; return HttpResponse.json({ ...ONB, step: 5, finished_at: '2026-10-07T10:00:00Z' }); }),
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json({ rows: [], tasks: [], window: [], lines: [], over_budget: [], allocated: [], over_day: [], checks: [], problems: [], push_blockers: [], days: [], stale_phases: [], by_hand: [], last_version_at: null, issues: [] })),
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
    fireEvent.click(screen.getByRole('button', { name: 'Finish onboarding' }));
    await waitFor(() => expect(saved).toEqual({ ...body, reason: 'Kick-off' }));
    await waitFor(() => expect(finished).toBe(true));
    expect(screen.queryByRole('button', { name: /Continue to/ })).toBeNull(); // the Master schedule is the last step
  });

  it('finishes without a new version when the schedule is unchanged, then opens the detail plan', async () => {
    let saves = 0;
    onboarding({ step: 5 });
    const phase = { phase: 'ITA', total_effort_h: 30, member_ids: ['an'], start: '2026-10-12', end: '2026-10-15', plan_md: 4 };
    const current = { id: 1, epic_key: 'PAY-1100', saved_at: '2026-10-07T09:00:00Z', saved_by: 'minh.hoang@acme.vn', reason: 'Kick-off', phases: [phase] };
    server.use(
      http.get('*/api/v1/planning/PAY-1100/master-schedule', () => HttpResponse.json({ estimation_phases: ['ITA'], current, versions: [current], shifts: [] })),
      http.post('*/api/v1/planning/PAY-1100/master-schedule/preview', async () => { await new Promise((r) => setTimeout(r, 500)); return HttpResponse.json([phase]); }),
      http.post('*/api/v1/planning/PAY-1100/master-schedule', () => { saves++; return HttpResponse.json({}); }),
      http.post('*/api/v1/onboarding/PAY-1100/finish', () => HttpResponse.json({ ...ONB, step: 5, finished_at: '2026-10-07T10:00:00Z' })),
      http.get('*/api/v1/onboarding', () => HttpResponse.json([])),
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json({ rows: [], tasks: [], window: [], lines: [], over_budget: [], allocated: [], over_day: [], checks: [], problems: [], push_blockers: [], days: [], stale_phases: [], by_hand: [], last_version_at: null, issues: [] })),
    );
    const { router } = renderApp('/onboarding/PAY-1100/schedule');
    fireEvent.click(await screen.findByRole('button', { name: 'Finish onboarding' })); // before the preview is back
    await waitFor(() => expect(router.state.location.pathname).toBe('/planning/detail'));
    expect(router.state.location.search).toEqual({ anken: 'PAY-1100' });
    expect(await screen.findByText('PAY-1100 is onboarded')).toBeTruthy();
    expect(saves).toBe(0);
  });

  it('waits for the epic before an anken without it can finish', async () => {
    const schedule = { estimation_phases: ['ITA'], current: null, versions: [], shifts: [] };
    server.use(
      http.get('*/api/v1/onboarding/draft-1', () => HttpResponse.json({ ...DRAFT, step: 5 })),
      http.get('*/api/v1/onboarding/draft-1/members', () => HttpResponse.json(PEOPLE)),
      http.get('*/api/v1/planning/draft-1/master-schedule', () => HttpResponse.json(schedule)),
    );
    renderApp('/onboarding/draft-1/schedule');
    expect(await screen.findByText('The onboarding finishes once the epic is linked.')).toBeTruthy();
    expect(screen.getAllByRole('link', { name: 'Link epic' }).map((l) => l.getAttribute('href'))).toContain('/onboarding/draft-1/epic');
    expect(screen.queryByRole('button', { name: 'Finish onboarding' })).toBeNull();
  });

  it('sends the old Detail plan step to Planning', async () => {
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json({ rows: [], tasks: [], window: [], lines: [], over_budget: [],
      allocated: [], over_day: [], checks: [], problems: [], push_blockers: [], days: [], stale_phases: [], by_hand: [], last_version_at: null, issues: [] })));
    const { router } = renderApp('/onboarding/PAY-1100/detail');
    await waitFor(() => expect(router.state.location.pathname).toBe('/planning/detail'));
    expect(router.state.location.search).toEqual({ anken: 'PAY-1100' });
  });
});
