import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { renderApp, server } from './renderApp';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); vi.restoreAllMocks(); });
afterAll(() => server.close());

const ONB = { epic_key: 'PAY-1100', title: 'Payment flow revision', anken_prefix: 'PAY', step: 3, started_at: '2026-10-05T09:00:00Z',
  started_by: 'minh.hoang@acme.vn', saved_at: '2026-10-07T09:00:00Z', saved_by: 'minh.hoang@acme.vn', finished_at: null };
const PEOPLE = [
  { member_id: 'an', name: 'An', role: 'BA', country: 'VN', in_anken: true, hours_per_day: 8 },
  { member_id: 'minh', name: 'Minh', role: 'BAL', country: 'VN', in_anken: true, hours_per_day: null },
  { member_id: 'tanaka', name: 'Tanaka', role: 'JM', country: 'JP', in_anken: false, hours_per_day: null },
];
const TASK = { key: 'PAY-1102', summary: '[ITA] Create test case', phase: 'ITA' };
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
      http.get('*/api/v1/onboarding/PAY-1500/tasks', () => HttpResponse.json({ anken_prefix: '', task_rule: '[{anken_prefix}][LocalTask] {task}', tasks: [] })),
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

  it('creates the missing template Tasks with their reviewed names', async () => {
    let created: { anken_prefix: string; assignee_id: string; tasks: unknown[] } | null = null;
    onboarding({ step: 2 });
    server.use(
      http.get('*/api/v1/onboarding/PAY-1100/tasks', ({ request }) => {
        const prefix = new URL(request.url).searchParams.get('anken_prefix') ?? '';
        return HttpResponse.json({ anken_prefix: prefix, task_rule: '[{anken_prefix}][LocalTask] {task}', tasks: [
          { match: '[*][LocalTask] ITA Prepare', phase: 'ITA', existing_key: null, name: `[${prefix}][LocalTask] ITA Prepare` },
          { match: '[Prepare] QnA management', phase: 'Prepare', existing_key: 'PAY-1101', name: '[Prepare] QnA management' },
        ] });
      }),
      http.post('*/api/v1/onboarding/PAY-1100/tasks/preview', () => HttpResponse.json({ updates: [], creates: [
        { ref: 'task:0', subtask: false, parent: 'PAY-1100', summary: '[PAY][LocalTask] ITA Prepare', request: { method: 'POST', path: '/rest/api/3/issue', body: '{}' } },
      ] })),
      http.post('*/api/v1/onboarding/PAY-1100/tasks', async ({ request }) => { created = await request.json() as typeof created; return HttpResponse.json({ updated: [], created: { 'task:0': 'PAY-1600' } }); }),
    );
    renderApp('/onboarding/PAY-1100/tasks');
    expect(await screen.findByDisplayValue('[PAY][LocalTask] ITA Prepare')).toBeTruthy();
    expect(screen.getByText('1 found · 1 missing')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Review 1 Tasks' }));
    const review = await screen.findByRole('region', { name: 'Tasks to create' });
    fireEvent.click(within(review).getByRole('button', { name: 'Create in Jira' }));
    await waitFor(() => expect(created).toEqual({ anken_prefix: 'PAY', assignee_id: 'minh', tasks: [{ match: '[*][LocalTask] ITA Prepare', name: '[PAY][LocalTask] ITA Prepare' }] }));
    expect(await screen.findByText('Created 1 Tasks in Jira')).toBeTruthy();
  });

  it('saves the estimation with the Task of each row, then the progress', async () => {
    let saved: { rows: Record<string, unknown>[] } | null = null;
    const progress = onboarding();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/estimation', () => HttpResponse.json({ rows: [LOGIN], phases: [{ phase: 'ITA', total_effort_h: 14 }], tasks: [TASK] })),
      http.put('*/api/v1/planning/PAY-1100/estimation', async ({ request }) => {
        saved = await request.json() as typeof saved;
        return HttpResponse.json({ rows: [LOGIN], phases: [{ phase: 'ITA', total_effort_h: 30 }], tasks: [TASK] });
      }),
    );
    renderApp('/onboarding/PAY-1100/estimation');
    const totals = await screen.findByRole('region', { name: 'Effort per phase' });
    expect(within(totals).getByText('14h')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Add row' }));
    fireEvent.change(screen.getByLabelText('Task, row 2'), { target: { value: 'PAY-1102' } });
    expect((screen.getByLabelText('Phase, row 2') as HTMLInputElement).value).toBe('ITA'); // filled from the Task
    fireEvent.change(screen.getByLabelText('Subphase, row 2'), { target: { value: 'Execute' } });
    fireEvent.change(screen.getByLabelText('Function, row 2'), { target: { value: 'Run' } });
    fireEvent.change(screen.getByLabelText('Steps, row 2'), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText('Effort (h), row 2'), { target: { value: '16' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(saved?.rows).toHaveLength(2));
    expect(saved!.rows[1]).toMatchObject({ id: 0, phase: 'ITA', subphase: 'Execute', function_name: 'Run', steps: null, effort_h: 16, task_key: 'PAY-1102' });
    await waitFor(() => expect(progress).toContainEqual({ step: 3 }));
  });

  it('adds valid imported rows and lists the bad ones', async () => {
    let puts = 0;
    onboarding();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/estimation', () => HttpResponse.json({ rows: [], phases: [], tasks: [TASK] })),
      http.put('*/api/v1/planning/PAY-1100/estimation', () => { puts++; return HttpResponse.json({ rows: [], phases: [], tasks: [] }); }),
      http.post('*/api/v1/planning/PAY-1100/estimation/import', () => HttpResponse.json([
        { line: 2, phase: 'ITA', subphase: 'Create test case', function: 'Login', steps: 5, effort_h: 8, errors: [] },
        { line: 3, phase: 'ITA', subphase: 'Create test case', function: '', steps: 5, effort_h: 8, errors: ['Function is required'] },
      ])),
    );
    renderApp('/onboarding/PAY-1100/estimation');
    const file = await screen.findByLabelText('Import file');
    fireEvent.change(file, { target: { files: [new File(['x'], 'e.csv', { type: 'text/csv' })] } });
    expect(await screen.findByText('Line 3: Function is required')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Add 1 row' }));
    expect((screen.getByLabelText('Function, row 1') as HTMLInputElement).value).toBe('Login');
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
    await waitFor(() => expect(saved).toEqual({ members: [{ member_id: 'tanaka', in_anken: true, hours_per_day: 1 }] }));
    await waitFor(() => expect(progress).toContainEqual({ step: 5 }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/onboarding/PAY-1100/schedule'));
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
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json({ rows: [], tasks: [], plan: [], over_budget: [], over_day: [], checks: [], problems: [] })),
    );
    renderApp('/onboarding/PAY-1100/schedule');
    fireEvent.change(await screen.findByLabelText('Start date'), { target: { value: '2026-10-12' } });
    const body = { phases: [{ phase: 'ITA', start: '2026-10-12', member_ids: ['an'] }, { phase: 'ITB', start: null, member_ids: ['an'] }] };
    await waitFor(() => expect(previews).toContainEqual(body));
    const gantt = await screen.findByRole('region', { name: 'Gantt by phase' });
    expect(within(gantt).getByText('16/10 – 16/10')).toBeTruthy();
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
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json({ rows: [], tasks: [], plan: [], over_budget: [], over_day: [], checks: [], problems: [] })),
    );
    const { router } = renderApp('/onboarding/PAY-1100/schedule');
    fireEvent.click(await screen.findByRole('button', { name: 'Continue to Detail plan' })); // before the preview is back
    await waitFor(() => expect(router.state.location.pathname).toBe('/onboarding/PAY-1100/detail'));
    expect(saves).toBe(0);
  });

  const detailView = (over: Record<string, unknown> = {}) => ({
    rows: [LOGIN, RUN], tasks: [TASK], plan: [], over_budget: [], over_day: [], checks: [], problems: [], ...over,
  });
  const planned = {
    plan: [{ row_id: 2, steps: [{ ref: '2', step: null, member_id: 'an', hours: 16, start: '2026-10-12', end: '2026-10-14' }] }],
    checks: [{ phase: 'ITA', master_end: '2026-10-13', plan_end: '2026-10-14', days_late: 1 }],
  };

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
    expect(await screen.findByText('Row 1 (Login): pick a BA')).toBeTruthy();
    expect(screen.getAllByText('PAY-1102 · [ITA] Create test case')).toHaveLength(2);
    fireEvent.change(screen.getByLabelText('BA, Login'), { target: { value: 'an' } });
    await waitFor(() => expect(saved).toEqual({ order: [1, 2], assigns: [{ row_id: 1, ba_id: 'an', bal_id: null, jm_id: null }, { row_id: 2, ba_id: 'an', bal_id: null, jm_id: null }] }));
    expect(await screen.findByText('12/10 – 14/10')).toBeTruthy();
    expect(screen.getByText('ITA ends 14/10 · master 13/10 · 1 day late')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Finish onboarding' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('blocks the push while a member is over budget', async () => {
    onboarding({ step: 6 });
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({
      ...planned, over_budget: [{ member_id: 'an', phase: 'ITA', planned_h: 40, budget_h: 36 }],
    }))));
    renderApp('/onboarding/PAY-1100/detail');
    expect(await screen.findByText('An is over budget in ITA: 40h of 36h')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Review push' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('pushes the plan, then finishes the onboarding', async () => {
    let finished = false;
    let pushedRows = false;
    onboarding({ step: 6 });
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
    const review = await screen.findByRole('region', { name: 'Push review' });
    expect(within(review).getByText('[ITA] Run')).toBeTruthy();
    fireEvent.click(within(review).getByRole('button', { name: 'Push to Jira' }));
    expect(await screen.findByText('Created 1 sub-tasks in Jira')).toBeTruthy();
    const finish = screen.getByRole('button', { name: 'Finish onboarding' }) as HTMLButtonElement;
    await waitFor(() => expect(finish.disabled).toBe(false));
    fireEvent.click(finish);
    await waitFor(() => expect(finished).toBe(true));
    await waitFor(() => expect(router.state.location.pathname).toBe('/anken/PAY-1100'));
  });
});
