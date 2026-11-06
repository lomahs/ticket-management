import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { renderApp, server } from './renderApp';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); vi.restoreAllMocks(); });
afterAll(() => server.close());

const ONB = { epic_key: 'PAY-1100', title: 'Payment flow revision', anken_prefix: 'PAY', labels: [] as string[], step: 3, started_at: '2026-10-05T09:00:00Z',
  started_by: 'minh.hoang@acme.vn', saved_at: '2026-10-07T09:00:00Z', saved_by: 'minh.hoang@acme.vn', finished_at: null };
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
    expect(within(totals).getByText('14h')).toBeTruthy();
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
    expect(within(screen.getByRole('region', { name: 'Team totals' })).getByText('1h')).toBeTruthy(); // JM hours
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
    expect(within(pop).getByText('PAY-1300').closest('div')?.textContent).toBe('PAY-1300 Refund4h');
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
    expect(screen.getByText('10h a day with other anken, more than the 8h working day')).toBeTruthy();
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
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json({ rows: [], tasks: [], plan: [], over_budget: [], allocated: [], over_day: [], checks: [], problems: [], steps: [], days: [], stale_phases: [], by_hand: [] })),
    );
    renderApp('/onboarding/PAY-1100/schedule');
    fireEvent.change(await screen.findByLabelText('Start date'), { target: { value: '2026-10-12' } });
    const body = { phases: [{ phase: 'ITA', start: '2026-10-12', member_ids: ['an'] }, { phase: 'ITB', start: null, member_ids: ['an'] }] };
    await waitFor(() => expect(previews).toContainEqual(body));
    const gantt = await screen.findByRole('region', { name: 'Gantt by phase' });
    expect(within(gantt).getByText('16/10 – 16/10')).toBeTruthy();
    // one column per day, Monday 12 to the Sunday after the last end, the weekend shaded
    const days = within(gantt).getByRole('row', { name: 'Days' });
    expect([...days.querySelectorAll('[title]')].map((d) => d.getAttribute('title'))).toEqual(
      ['Mon 12/10', 'Tue 13/10', 'Wed 14/10', 'Thu 15/10', 'Fri 16/10', 'Sat 17/10', 'Sun 18/10']);
    expect([...days.querySelectorAll('[data-weekend]')].map((d) => d.getAttribute('title'))).toEqual(['Sat 17/10', 'Sun 18/10']);
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
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json({ rows: [], tasks: [], plan: [], over_budget: [], allocated: [], over_day: [], checks: [], problems: [], steps: [], days: [], stale_phases: [], by_hand: [] })),
    );
    const { router } = renderApp('/onboarding/PAY-1100/schedule');
    fireEvent.click(await screen.findByRole('button', { name: 'Continue to Detail plan' })); // before the preview is back
    await waitFor(() => expect(router.state.location.pathname).toBe('/onboarding/PAY-1100/detail'));
    expect(saves).toBe(0);
  });

  const line = (ref: string, row: number, step: string | null, summary: string, h: number, dates: [string, string] | null = null) => ({
    ref, row_id: row, step, phase: 'ITA', summary, effort_h: h, ba_h: step === 'balr' || step === 'jmr' ? 0 : h, bal_h: step === 'balr' ? h : 0,
    start: dates?.[0] ?? null, end: dates?.[1] ?? null, manual: false, edited: false, removed: false,
  });
  const LINES = [line('1:create', 1, 'create', '[PAY] Login – Create', 8), line('1:balr', 1, 'balr', '[PAY] Login – BAL Review', 2),
    line('2', 2, null, '[PAY] Run', 16)];
  const detailView = (over: Record<string, unknown> = {}) => ({
    rows: [LOGIN, RUN], tasks: [TASK], plan: [], steps: LINES, over_budget: [], allocated: [], over_day: [], checks: [], days: [],
    stale_phases: [], problems: [], by_hand: [], ...over,
  });
  const planned = {
    plan: [{ row_id: 2, steps: [{ ref: '2', step: null, member_id: 'an', hours: 16, start: '2026-10-12', end: '2026-10-14' }] }],
    steps: [...LINES.slice(0, 2), line('2', 2, null, '[PAY] Run', 16, ['2026-10-12', '2026-10-14'])],
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

  it('assigns members and shows computed dates', async () => {
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
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(await screen.findByText('Row 1 (Login): pick a BA')).toBeTruthy();
    expect(screen.getByText('[PAY] Login – BAL Review')).toBeTruthy(); // one line per step, named by the name rules
    fireEvent.change(screen.getByLabelText('BA, Login'), { target: { value: 'an' } });
    await waitFor(() => expect(saved).toEqual({ order: [1, 2], assigns: [{ row_id: 1, ba_id: 'an', bal_id: null, jm_id: null }, { row_id: 2, ba_id: 'an', bal_id: null, jm_id: null }] }));
    await waitFor(() => expect((screen.getByLabelText('Plan start, [PAY] Run') as HTMLInputElement).value).toBe('2026-10-12'));
    expect((screen.getByLabelText('Plan end, [PAY] Run') as HTMLInputElement).value).toBe('2026-10-14');
    expect(screen.getByText('ITA ends 14/10 · master 13/10 · 1 day late')).toBeTruthy();
    expect(within(screen.getByRole('row', { name: '[PAY] Login – Create' })).getByText('An 8h')).toBeTruthy(); // who carries the line
    fireEvent.click(screen.getByRole('tab', { name: 'Effort per day' }));
    const days = screen.getByRole('region', { name: 'Effort per member per day' });
    const tue = within(days).getByLabelText('An 2026-10-13');
    expect(tue.textContent).toBe('9+3 other'); // the planned hours only, red above the 8h allocation
    expect(within(days).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Member', 'Mon', 'Tue', '10/12', '10/13']); // no weekend
    fireEvent.mouseEnter(tue);
    const tip = screen.getByRole('tooltip');
    expect(days.contains(tip)).toBe(false); // above the page, not clipped by the section
    expect(tip.textContent).toContain('PAY-1290 · Refund – Create'); // a task of this anken
    expect(tip.textContent).toContain('Other anken');
    expect(tip.textContent).toContain('Refund3h'); // another anken: its name and hours
    expect(screen.getByRole('button', { name: 'Finish onboarding' }).getAttribute('title')).toBe('Push every row to Jira first');
  });

  it('sends a date set by hand once it is left, not while it is typed', async () => {
    const sent: Record<string, unknown>[] = [];
    onboarding({ step: 6 });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView(planned))),
      http.put('*/api/v1/planning/PAY-1100/detail/step', async ({ request }) => {
        sent.push(await request.json() as Record<string, unknown>);
        return HttpResponse.json(detailView({ ...planned, steps: [...LINES.slice(0, 2), { ...line('2', 2, null, '[PAY] Run', 16, ['2026-10-15', '2026-10-15']), manual: true }] }));
      }),
    );
    renderApp('/onboarding/PAY-1100/detail');
    const start = await screen.findByLabelText('Plan start, [PAY] Run') as HTMLInputElement;
    fireEvent.change(start, { target: { value: '0002-10-15' } }); // the year half typed
    fireEvent.change(start, { target: { value: '2026-10-15' } });
    expect(sent).toEqual([]);
    fireEvent.blur(start);
    await waitFor(() => expect(sent).toEqual([{ row_id: 2, step: null, effort_h: null, start: '2026-10-15', end: '2026-10-15', removed: false }]));
    await waitFor(() => expect((screen.getByLabelText('Plan start, [PAY] Run') as HTMLInputElement).value).toBe('2026-10-15'));
    // set by hand: the line is highlighted with a lock
    expect(within(screen.getByRole('row', { name: '[PAY] Run' })).getByRole('img', { name: 'Dates set by hand' })).toBeTruthy();
    expect(within(screen.getByRole('row', { name: '[PAY] Login – Create' })).queryByRole('img', { name: 'Dates set by hand' })).toBeNull();
    const end =screen.getByLabelText('Plan end, [PAY] Run') as HTMLInputElement;
    fireEvent.change(end, { target: { value: '0020-10-20' } });
    fireEvent.blur(end); // not a date of the plan: put back
    expect(end.value).toBe('2026-10-15');
    expect(sent).toHaveLength(1);
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
        return HttpResponse.json(detailView({ steps: LINES.map((l) => (l.ref === '1:balr' ? { ...l, removed: !!b.removed } : l)) }));
      }),
      http.post('*/api/v1/planning/PAY-1100/detail/rows', async ({ request }) => {
        added = await request.json() as Record<string, unknown>;
        return HttpResponse.json(detailView());
      }),
    );
    renderApp('/onboarding/PAY-1100/detail');
    const effort = await screen.findByLabelText('Effort, [PAY] Login – BAL Review');
    fireEvent.change(effort, { target: { value: '3' } });
    fireEvent.blur(effort);
    await waitFor(() => expect(steps[0]).toEqual({ row_id: 1, step: 'balr', effort_h: 3, start: null, end: null, removed: false }));
    fireEvent.click(screen.getByRole('button', { name: 'Remove [PAY] Login – BAL Review' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Removed (1)' }));
    expect(screen.queryByRole('row', { name: '[PAY] Login – BAL Review' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }));
    await waitFor(() => expect(steps.map((b) => b.removed)).toEqual([false, true, false]));

    fireEvent.click(screen.getByRole('button', { name: 'Add function' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add function' });
    fireEvent.change(within(dialog).getByLabelText('Function'), { target: { value: 'Export' } });
    fireEvent.change(within(dialog).getByLabelText('Create effort (h)'), { target: { value: '6' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(added).toEqual({ task_key: 'PAY-1102', function_name: 'Export', steps: 5, effort_h: 6 }));
  });

  it('warns about days above the allocation and pushes them once confirmed', async () => {
    let allowed: string | null = null;
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
    await waitFor(() => expect(allowed).toBe('true'));
    const dialog = await screen.findByRole('dialog', { name: 'Push review' }); // the warnings are confirmed in the review
    expect(within(dialog).getByRole('note', { name: 'Days above the allocation' }).textContent).toContain('An has 9h planned on 13/10, above the 8h allocation');
    const warn = screen.getByRole('region', { name: 'Over the allocation' });
    expect(warn.textContent).toContain('An has 9h planned on 13/10, above the 8h allocation');
    expect(screen.queryByRole('alert', { name: 'Before the push' })).toBeNull(); // a warning, not a block
  });

  it('blocks the push while a member is over budget', async () => {
    onboarding({ step: 6 });
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({
      ...planned, over_budget: [{ member_id: 'an', phase: 'ITA', planned_h: 40, budget_h: 36 }],
      allocated: [{ member_id: 'an', phase: 'ITA', date: '2026-10-12', ankens: [
        { epic_key: 'PAY-1100', title: 'Payment', hours_per_day: 3 }, { epic_key: 'CRM-400', title: 'CRM', hours_per_day: 5 }] }],
    }))));
    renderApp('/onboarding/PAY-1100/detail');
    fireEvent.click(await screen.findByRole('button', { name: 'Finish onboarding' })); // not every row is in Jira: shows what stands before
    expect(await screen.findByText('An is over budget in ITA: 40h of 36h')).toBeTruthy();
    const matrix = screen.getByRole('table', { name: 'Allocated per anken' });
    expect(matrix.textContent).toContain('PAY-1100 (this)');
    expect(screen.getByRole('row', { name: /An · ITA \(from 12\/10\)/ }).textContent).toBe('An · ITA (from 12/10)3h5h8h');
    expect((screen.getByRole('button', { name: 'Review push' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('pushes the plan, then finishes the onboarding', async () => {
    let finished = false;
    let pushedRows = false;
    onboarding({ step: 6, labels: ['PAY', 'phase'] });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(pushedRows
        ? detailView({ rows: [{ ...LOGIN, pushed_at: '2026-10-07T09:00:00Z' }, { ...RUN, pushed_at: '2026-10-07T09:00:00Z' }] }) : detailView(planned))),
      http.post('*/api/v1/planning/PAY-1100/detail/push/preview', () => HttpResponse.json({ updates: [], creates: [
        { ref: '2', subtask: true, parent: 'PAY-1102', summary: '[ITA] Run', request: { method: 'POST', path: '/rest/api/3/issue', body: '{}' } },
      ] })),
      http.post('*/api/v1/planning/PAY-1100/detail/push', () => { pushedRows = true; return HttpResponse.json({ updated: [], created: { 2: 'PAY-1502' } }); }),
      http.post('*/api/v1/onboarding/PAY-1100/finish', () => { finished = true; return HttpResponse.json({ ...ONB, step: 6, finished_at: '2026-10-07T10:00:00Z' }); }),
      http.get('*/api/v1/onboarding', () => HttpResponse.json([])),
    );
    const { router } = renderApp('/onboarding/PAY-1100/detail');
    fireEvent.click(await screen.findByRole('button', { name: 'Review push' }));
    const review = await screen.findByRole('dialog', { name: 'Push review' });
    expect(within(review).getByText('[ITA] Run')).toBeTruthy();
    expect(within(review).getByText('Labels PAY, phase (Setting Phase)')).toBeTruthy();
    fireEvent.click(within(review).getByRole('button', { name: 'Push to Jira' }));
    expect(await screen.findByText('Created 1 sub-tasks in Jira')).toBeTruthy();
    const finish = screen.getByRole('button', { name: 'Finish onboarding' }) as HTMLButtonElement;
    await waitFor(() => expect(finish.disabled).toBe(false));
    fireEvent.click(finish);
    await waitFor(() => expect(finished).toBe(true));
    await waitFor(() => expect(router.state.location.pathname).toBe('/anken/PAY-1100'));
  });
});
